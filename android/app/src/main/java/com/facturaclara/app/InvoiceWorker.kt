package com.facturaclara.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.math.floor
import kotlin.math.sqrt

/** One document per persistent WorkManager job; pages are processed sequentially. */
class InvoiceWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    companion object {
        const val MAX_PAGES = 50
        const val MAX_FILE_BYTES = 40L * 1024 * 1024
        private const val MAX_SOURCE_PIXELS = 100_000_000L
        private const val MAX_OCR_PIXELS = 10_000_000L
        private const val MAX_SIDE = 12000
        private const val PADDLE_FRAGMENT_CHUNK = 100
    }

    private val repository = Repository(applicationContext)
    private val pixelBudget = (Runtime.getRuntime().maxMemory() / 24)
        .coerceIn(1_000_000L, MAX_OCR_PIXELS)
    private val lifecycle = Any()
    @Volatile private var cancellationRequested = false
    private var ownerThread: Thread? = null
    private var activeIndependent: IndependentOcr? = null
    private var activeHelper: IndependentOcr? = null
    private var activePaddle: PaddleOcr? = null
    private var activeDetector: PaddleTextDetector? = null

    override fun doWork(): Result {
        synchronized(lifecycle) {
            if (isStopped || cancellationRequested) return Result.success()
            ownerThread = Thread.currentThread()
        }
        try { return processDocument() }
        finally {
            // onStopped must never interrupt this executor thread after it has
            // moved on to a different job.
            synchronized(lifecycle) {
                ownerThread = null; activeIndependent = null; activeHelper = null; activePaddle = null; activeDetector = null
            }
        }
    }

    override fun onStopped() {
        val (tesseract, paddle, detector) = synchronized(lifecycle) {
            cancellationRequested = true
            // Worker 2.10 cancels its future without interrupting doWork itself.
            // Interrupt Tasks.await; OfflineOcr retains its pending input safely.
            ownerThread?.interrupt()
            Triple(listOfNotNull(activeIndependent, activeHelper), activePaddle, activeDetector)
        }
        // Native stop is thread-safe; disposing still belongs to the read owner.
        tesseract.forEach { it.cancel() }
        paddle?.cancel()
        detector?.cancel()
        super.onStopped()
    }

    private fun processDocument(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val invoice = repository.get(id) ?: return Result.success()
        val pages = mutableListOf<ScanPage>()
        try {
            checkNotStopped()
            invoice.status = "processing"
            invoice.error = null
            invoice.pdfPath = null
            invoice.pages = emptyList()
            saveWhileRunning(invoice)
            val source = File(invoice.sourcePath)
            require(source.isFile && source.length() > 0) { "El archivo original no existe o está vacío." }
            require(source.length() <= MAX_FILE_BYTES) { "El archivo supera 40 MB. Divídalo en archivos más pequeños." }
            val directory = repository.documentDir(id)
            if (!directory.exists() && !directory.mkdirs()) throw IOException("No se pudo crear la carpeta del documento.")
            OfflineOcr().use { engine ->
                if (invoice.mime == "application/pdf" || source.extension.equals("pdf", true)) {
                    scanPdf(source, directory, engine) { page ->
                        pages += page
                        invoice.pages = pages.toList()
                        saveWhileRunning(invoice)
                    }
                } else {
                    checkNotStopped()
                    val preview = File(directory, "page-001.png")
                    val page = try {
                        val (bitmap, warnings) = loadImage(source)
                        transcribePage(bitmap, preview, 0, warnings, engine)
                    } catch (error: InterruptedException) {
                        throw error
                    } catch (error: Exception) {
                        failedPage(0, preview, readableError(error))
                    } catch (error: OutOfMemoryError) {
                        failedPage(0, preview, "Memoria insuficiente para esta imagen. Reduzca su resolución.")
                    }
                    pages += page
                    invoice.pages = pages.toList()
                    saveWhileRunning(invoice)
                }
            }
            checkNotStopped()
            check(pages.isNotEmpty()) { "El documento no contiene páginas." }
            // Only pages without any recognized text prevent the PDF. Unconfirmed pages
            // are still exported with their primary reading, highlighted for review.
            val unreadable = pages.filter { it.text.isBlank() }
            if (unreadable.isNotEmpty()) {
                invoice.status = "needs_recapture"
                invoice.error = "No se generó el PDF: ${unreadable.size} página(s) sin texto legible."
            } else {
                val review = pages.any { it.blockers.isNotEmpty() }
                invoice.pages = pages.toList()
                checkNotStopped()
                val output = PdfExporter(applicationContext).generate(invoice, allowReview = review)
                checkNotStopped()
                invoice.pdfPath = output.absolutePath
                invoice.status = if (review) "review" else "ready"
            }
        } catch (error: InterruptedException) {
            invoice.status = "failed"
            invoice.error = "El escaneo se interrumpió. Las páginas ya procesadas se conservaron; vuelva a intentarlo."
            Thread.currentThread().interrupt()
        } catch (error: OutOfMemoryError) {
            invoice.status = "failed"
            invoice.error = "Memoria insuficiente. Divida el documento o reduzca la resolución."
        } catch (error: Exception) {
            invoice.status = "failed"
            invoice.error = readableError(error)
        } catch (_: LinkageError) {
            invoice.status = "failed"
            invoice.pdfPath = null
            invoice.error = "No se pudo completar el escaneo porque un componente local no está disponible en este dispositivo."
        } finally {
            synchronized(lifecycle) {
                invoice.pages = pages.toList()
                if (stopped()) markCancelled(invoice)
                repository.save(invoice)
                // isStopped can change before onStopped obtains the lock, even
                // during the file write. Do not leave a newly published ready PDF.
                if (stopped() && invoice.status != "failed") {
                    markCancelled(invoice)
                    repository.save(invoice)
                }
                ownerThread = null
            }
        }
        // Document errors are stored visibly; they must not cancel later jobs in a batch.
        return Result.success()
    }

    private fun saveWhileRunning(invoice: Invoice) = synchronized(lifecycle) {
        checkNotStopped()
        repository.save(invoice)
        checkNotStopped()
    }

    private fun markCancelled(invoice: Invoice) {
        invoice.status = "failed"
        invoice.pdfPath = null
        invoice.error = "El escaneo se interrumpió. Las páginas ya procesadas se conservaron; vuelva a intentarlo."
    }

    /** Paddle and Tesseract readings of each fragment, voted with ML Kit's contrast pass. */
    private fun voteFragments(bitmap: Bitmap, lines: List<OcrLine>, second: OfflineOcr.Reading,
        tesseractPage: TesseractPage): List<OcrLine> = try {
        val tesseractAssigned = tesseractPage.read().map { OpticalCellAssignment.assign(it, lines, bitmap.width, bitmap.height) }
        checkNotStopped()
        val contrastAssigned = OpticalCellAssignment.assign(second.words, lines, bitmap.width, bitmap.height)
        fun joined(words: List<OcrLine>) = words.sortedBy { it.left }.joinToString(" ") { it.text }
        val others = lines.indices.map { index ->
            buildList {
                contrastAssigned[index].takeIf { it.isNotEmpty() }?.let { add(EngineVote.Candidate(EngineVote.Family.ML, joined(it))) }
                tesseractAssigned.forEach { assigned ->
                    assigned[index].takeIf { it.isNotEmpty() }?.let { add(EngineVote.Candidate(EngineVote.Family.TESSERACT, joined(it))) }
                }
            }
        }
        // Paddle's five crops per fragment are read only where they could change the vote.
        val consulted = lines.indices.filter { EngineVote.paddleCanChange(lines[it].text, others[it]) }
        val paddleReadings = if (consulted.isEmpty()) emptyList() else PaddleOcr(applicationContext).use { paddle ->
            synchronized(lifecycle) { checkNotStopped(); activePaddle = paddle }
            try {
                // Paddle bounds one request; long pages are read in chunks with their indices kept.
                consulted.chunked(PADDLE_FRAGMENT_CHUNK).flatMap { chunk ->
                    paddle.recognize(bitmap, chunk.map { lines[it] }).map { it.copy(index = chunk[it.index]) }
                }
            } finally { synchronized(lifecycle) { if (activePaddle === paddle) activePaddle = null } }
        }
        checkNotStopped()
        lines.mapIndexed { index, line ->
            val candidates = others[index] + paddleReadings.firstOrNull { it.index == index }?.observations.orEmpty().map {
                EngineVote.Candidate(EngineVote.Family.PADDLE, it.text)
            }
            val text = EngineVote.choose(line.text, candidates)
            if (text == line.text) line else line.copy(text = text)
        }
    } catch (error: InterruptedException) { throw error }
    catch (_: Exception) { lines }
    catch (_: LinkageError) { lines }

    private data class Recovery(val lines: List<OcrLine>, val warning: String? = null)

    /**
     * Reads the text-detector boxes that neither a final line nor a line removed on purpose
     * ([removed], e.g. decorative rows) covers, and returns the agreed readings as doubtful
     * lines (see [UncoveredText]). A box Paddle cannot read is skipped on its own; if the
     * detector itself fails, the page is kept as read and says so.
     */
    private fun recoverUncovered(bitmap: Bitmap, lines: List<OcrLine>, removed: List<OcrLine>): Recovery {
        if (lines.isEmpty()) return Recovery(emptyList())
        val boxes = try {
            PaddleTextDetector(applicationContext).use { detector ->
                synchronized(lifecycle) { checkNotStopped(); activeDetector = detector }
                try { detector.detect(bitmap).detections.map { it.line } }
                finally { synchronized(lifecycle) { if (activeDetector === detector) activeDetector = null } }
            }
        } catch (error: InterruptedException) { throw error }
        catch (_: Exception) { null }
        catch (_: LinkageError) { null }
            ?: return Recovery(emptyList(), "No se pudo buscar texto que el lector principal no haya visto en esta página.")
        checkNotStopped()
        val open = UncoveredText.uncovered(boxes, lines + removed).map { boxes[it] }.filter(UncoveredText::lineSized)
        if (open.isEmpty()) return Recovery(emptyList())
        val readings = try {
            PaddleOcr(applicationContext).use { paddle ->
                synchronized(lifecycle) { checkNotStopped(); activePaddle = paddle }
                try {
                    fun read(group: List<OcrLine>) = paddle.recognize(bitmap, group).map { group[it.index] to it.observations }
                    open.chunked(PADDLE_FRAGMENT_CHUNK).flatMap { chunk ->
                        // One unreadable box must not cost the others their reading.
                        try { read(chunk) } catch (error: InterruptedException) { throw error } catch (_: Exception) {
                            chunk.flatMap { box -> try { read(listOf(box)) } catch (error: InterruptedException) { throw error } catch (_: Exception) { emptyList() } }
                        }
                    }
                } finally { synchronized(lifecycle) { if (activePaddle === paddle) activePaddle = null } }
            }
        } catch (error: InterruptedException) { throw error }
        catch (_: Exception) { return Recovery(emptyList(), "No se pudo leer el texto que el lector principal no vio en esta página.") }
        catch (_: LinkageError) { return Recovery(emptyList(), "No se pudo leer el texto que el lector principal no vio en esta página.") }
        return Recovery(readings.mapNotNull { (box, observations) ->
            val agreed = UncoveredText.agreed(observations.map { UncoveredText.Reading(it.text, it.confidence, it.rasterId) })
            agreed?.takeUnless { UncoveredText.alreadyRead(it.text, box, lines) }?.let { UncoveredText.recovered(box, it) }
        })
    }

    /**
     * Complete-page Tesseract observations of one page bitmap. The reader is deterministic
     * for the same pixels, so the recovery and the vote share a single read.
     */
    private inner class TesseractPage(private val bitmap: Bitmap) {
        private var readings: List<List<OcrLine>>? = null

        fun read(reader: IndependentOcr): List<List<OcrLine>> =
            readings ?: bothPasses(reader).also { readings = it }

        fun read(): List<List<OcrLine>> = readings
            ?: IndependentOcr(applicationContext).use { reader -> withIndependent(reader) { read(reader) } }

        /** The two passes are separate reads, so the second runs on its own engine meanwhile. */
        private fun bothPasses(reader: IndependentOcr): List<List<OcrLine>> = IndependentOcr(applicationContext).use { helper ->
            synchronized(lifecycle) { checkNotStopped(); activeHelper = helper }
            val second = FutureTask { helper.recognizePass(bitmap, 1) }
            val thread = Thread(second, "tesseract-second-pass")
            try {
                thread.start()
                val first = reader.recognizePass(bitmap, 0)
                listOf(first, try { second.get() } catch (error: ExecutionException) { throw error.cause ?: error })
            } finally {
                // The helper reads the caller's bitmap; it must have stopped before returning.
                helper.cancel()
                var interrupted = false
                while (true) {
                    try { thread.join(); break } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                synchronized(lifecycle) { if (activeHelper === helper) activeHelper = null }
            }
        }
    }

    private fun <T> withIndependent(reader: IndependentOcr, read: () -> T): T {
        synchronized(lifecycle) { checkNotStopped(); activeIndependent = reader }
        try { return read() }
        finally { synchronized(lifecycle) { if (activeIndependent === reader) activeIndependent = null } }
    }

    private fun scanPdf(
        source: File,
        directory: File,
        engine: OfflineOcr,
        savePage: (ScanPage) -> Unit,
    ) {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw IOException("No se pudo abrir el PDF. Puede estar dañado o protegido por contraseña; use una copia desbloqueada.", error)
        }
        renderer.use { pdf ->
            require(pdf.pageCount in 1..MAX_PAGES) {
                "El PDF tiene ${pdf.pageCount} páginas. Se permiten entre 1 y $MAX_PAGES; divídalo sin omitir páginas."
            }
            for (index in 0 until pdf.pageCount) {
                checkNotStopped()
                val preview = File(directory, "page-%03d.png".format(index + 1))
                val scanned = try {
                    pdf.openPage(index).use { page ->
                        require(page.width > 0 && page.height > 0) { "La página tiene dimensiones inválidas." }
                        val fullPixels = page.width.toDouble() * page.height * 9.0
                        val scale = minOf(3.0, sqrt(pixelBudget / (page.width.toDouble() * page.height)),
                            MAX_SIDE.toDouble() / maxOf(page.width, page.height))
                        val width = floor(page.width * scale).toInt().coerceAtLeast(1)
                        val height = floor(page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (error: Throwable) {
                            bitmap.recycle()
                            throw error
                        }
                        val warnings = if (fullPixels > pixelBudget || scale < 2.9) {
                            listOf("La resolución de esta página se redujo para limitar el uso de memoria.")
                        } else emptyList()
                        transcribePage(bitmap, preview, index, warnings, engine)
                    }
                } catch (error: InterruptedException) {
                    throw error
                } catch (error: Exception) {
                    failedPage(index, preview, readableError(error))
                } catch (error: OutOfMemoryError) {
                    failedPage(index, preview, "Memoria insuficiente para esta página. Divida el PDF o reduzca su resolución.")
                }
                savePage(scanned)
            }
        }
    }

    private fun loadImage(source: File): Pair<Bitmap, List<String>> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "La imagen está dañada o su formato no es compatible." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
            "La imagen supera 100 millones de píxeles. Reduzca la resolución antes de importarla."
        }
        // Avoid silently keeping only the first page of formats not supported by this decoder.
        require(bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/bmp")) {
            "Formato no compatible. Use JPEG, PNG, WebP o PDF; convierta los archivos multipágina a PDF."
        }
        val sizing = ImageDecodeSizing.plan(bounds.outWidth, bounds.outHeight, pixelBudget, MAX_SIDE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sizing.sampleSize
            inScaled = sizing.scales
            inDensity = sizing.sourceDensity
            inTargetDensity = sizing.targetDensity
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
            ?: throw IOException("No se pudo decodificar la imagen.")
        val warnings = mutableListOf<String>()
        try {
            require(bitmap.width.toLong() * bitmap.height <= pixelBudget &&
                maxOf(bitmap.width, bitmap.height) <= MAX_SIDE) {
                "No se pudo decodificar la imagen dentro del límite de memoria."
            }
            // Density here is only a decoding ratio. It must not scale the photo
            // again when Canvas flattens transparency or makes the OCR preview.
            bitmap.density = Bitmap.DENSITY_NONE
            if (bitmap.width < bounds.outWidth || bitmap.height < bounds.outHeight) {
                warnings += "Se redujo la resolución de la foto para limitar la memoria."
            }
            val orientation = try {
                ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) {
                ExifInterface.ORIENTATION_UNDEFINED
            }
            val matrix = orientationMatrix(orientation)
            if (matrix != null) {
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (oriented !== bitmap) bitmap.recycle()
                bitmap = oriented
                warnings += "Se corrigió la orientación indicada por la cámara."
            }
            if (bitmap.hasAlpha()) {
                val white = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(white)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()
                bitmap = white
            }
            return bitmap to warnings
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    private fun transcribePage(
        initial: Bitmap,
        preview: File,
        index: Int,
        inputWarnings: List<String>,
        engine: OfflineOcr,
    ): ScanPage {
        var bitmap = initial
        var sourceOwnedByPendingTask = false
        fun readSource(): OfflineOcr.Reading = try {
            engine.recognize(bitmap)
        } catch (error: InterruptedException) {
            sourceOwnedByPendingTask = true
            throw error
        }
        try {
            checkNotStopped()
            savePreview(bitmap, preview)
            checkNotStopped()
            var reading = readSource()
            checkNotStopped()
            val warnings = inputWarnings.toMutableList()
            if (reading.suggestedRotation != 0) {
                val matrix = Matrix().apply { setRotate(reading.suggestedRotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
                savePreview(bitmap, preview)
                checkNotStopped()
                reading = readSource()
                checkNotStopped()
                warnings += "Se corrigió automáticamente una página girada."
            }
            if (reading.suggestedRotation == 0 && reading.suggestedDeskew != 0f) {
                // A clearly tilted photo is straightened on a white page and read again;
                // rows then follow the paper instead of the camera's horizon.
                val (straightened, scale) = OfflineOcr.straighten(bitmap, reading.suggestedDeskew, pixelBudget)
                if (straightened !== bitmap) bitmap.recycle()
                bitmap = straightened
                if (scale < 1f) warnings += "Se redujo la resolución al enderezar la foto para limitar la memoria."
                savePreview(bitmap, preview)
                checkNotStopped()
                reading = readSource()
                checkNotStopped()
                warnings += "Se enderezó automáticamente una foto inclinada."
            }
            val blockers = reading.blockers.toMutableList()
            if (reading.suggestedRotation != 0) blockers += "No se pudo resolver la orientación de la página. Tome una nueva foto de frente."
            // Both passes share the same bundled model. Agreement catches some
            // preprocessing-sensitive errors; it does not prove a faithful transcription.
            checkNotStopped()
            val contrasted = OfflineOcr.contrastImage(bitmap)
            var contrastInterrupted = false
            val secondReading = try {
                engine.recognize(contrasted)
            } catch (error: InterruptedException) {
                contrastInterrupted = true
                throw error
            } finally {
                if (!contrastInterrupted && !contrasted.isRecycled) contrasted.recycle()
            }
            checkNotStopped()
            if (secondReading.text.isBlank()) {
                blockers += "La segunda lectura no pudo confirmar el texto. Tome una foto más nítida."
            } else {
                blockers += secondReading.blockers
                warnings += secondReading.warnings
                // Decorative asterisk rows are dropped from the output, so they must not
                // decide whether the two readings agree.
                val comparison = OcrConsistency.compare(DecorativeText.withoutDecoration(reading),
                    DecorativeText.withoutDecoration(secondReading))
                if (!comparison.numbersMatch) {
                    blockers += "Las dos lecturas detectaron cifras distintas. Tome una nueva captura con los números enfocados."
                }
                if (!comparison.contentMatches) {
                    blockers += "Las dos lecturas no coincidieron en todas las palabras, cifras o signos. Mejore la luz y vuelva a capturar la factura."
                }
            }
            var finalText = reading.text
            var finalLines = reading.lines
            val tesseractPage = TesseractPage(bitmap)
            // Regional recovery is for failed primary checks. Preserve the
            // established path when both complete readings already agree and
            // pass quality checks; extra resampling can introduce OCR errors.
            if (blockers.isNotEmpty() && reading.lines.isNotEmpty() && secondReading.lines.isNotEmpty() &&
                reading.captureBlockers.isEmpty() && secondReading.captureBlockers.isEmpty() &&
                reading.suggestedRotation == 0 && secondReading.suggestedRotation == 0) {
                val verified = recoverPage(bitmap, reading, secondReading, engine, preview, index, warnings, tesseractPage)
                checkNotStopped()
                if (verified.unresolved == 0) {
                    finalLines = verified.lines
                    finalText = OcrLayout.text(finalLines)
                    blockers.clear()
                    warnings += "Se contrastó el texto con lectores locales y se releyeron las zonas con diferencias. Estas comprobaciones no garantizan exactitud absoluta."
                } else {
                    // Keep the complete primary observation while the page is
                    // unresolved; do not publish a mixture of partial candidates.
                    blockers += "Quedan ${verified.unresolved} zona(s) sin una lectura confirmada. No se completan ni corrigen datos por suposición."
                }
            }
            // Word boxes describe the primary reading, not a recovered replacement.
            val primaryWords = if (finalLines === reading.lines) reading.words else emptyList()
            // Rows of printed asterisks are read as X/K garbage; they carry no content.
            val decorativeLines = finalLines.filter { DecorativeText.isDecoration(it.text) }
            val decorative = decorativeLines.size
            if (decorative > 0) {
                finalLines = finalLines.filterNot { DecorativeText.isDecoration(it.text) }
                finalText = OcrLayout.text(finalLines)
                warnings += "Se omitieron $decorative línea(s) decorativas de asteriscos."
            }
            if (blockers.isNotEmpty() && finalLines.isNotEmpty()) {
                // Unconfirmed page: let the other bundled readers outvote ML Kit per fragment.
                val voted = voteFragments(bitmap, finalLines, secondReading, tesseractPage)
                if (voted != finalLines) {
                    // A replaced fragment was contested by ML Kit: always show it for review.
                    finalLines = voted.mapIndexed { index, line -> if (line.text != finalLines[index].text) line.copy(doubtful = true) else line }
                    finalText = OcrLayout.text(finalLines)
                    warnings += "Algunos fragmentos dudosos se sustituyeron por la lectura en la que coincidieron dos lectores locales distintos."
                }
            }
            // Text the detector finds where no line was read (a bold total, white figures on a
            // dark bar) joins the page only as doubtful text that two Paddle rasters agree on.
            val recovery = recoverUncovered(bitmap, finalLines, decorativeLines)
            recovery.warning?.let { warnings += it }
            val recovered = recovery.lines
            if (recovered.isNotEmpty()) {
                finalLines = finalLines + recovered
                finalText = OcrLayout.text(finalLines)
                warnings += "Se recuperaron ${recovered.size} texto(s) que el lector principal no vio; están resaltados para revisar."
                blockers += "Hay texto leído por un solo lector local; revisa lo resaltado con el documento original."
            }
            val arithmetic = InvoiceChecks.inspect(finalText)
            warnings += arithmetic.warnings
            blockers += arithmetic.blockers
            blockers += InvoiceGeometryChecks.inspect(finalText, finalLines)
            finalLines.firstOrNull { ReadingReview.impossibleAmount(it.text) }?.let {
                blockers += "Un importe tiene separadores que no corresponden a ningún formato (por ejemplo «${it.text.trim().take(40)}»): puede ser una coma o un punto mal leídos o un error del propio documento. Revisa los importes resaltados."
            }
            // Text stays verbatim; unsupported fragments are only flagged for the PDF.
            if (blockers.isNotEmpty()) finalLines = ReadingReview.mark(finalLines, secondReading.lines,
                primaryWords, secondReading.words)
            return ScanPage(index = index, imagePath = preview.absolutePath,
                text = finalText, warnings = (warnings + reading.warnings).distinct(),
                blockers = blockers.distinct(), lines = finalLines)
        } finally {
            // Only interruption inside a source ML task transfers ownership to
            // OfflineOcr. Checkpoints and temporary/Tesseract reads do not retain it.
            if (!sourceOwnedByPendingTask && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun recoverPage(bitmap: Bitmap, first: OfflineOcr.Reading, second: OfflineOcr.Reading,
        engine: OfflineOcr, preview: File, index: Int, warnings: MutableList<String>,
        tesseractPage: TesseractPage): RegionalOcrVerifier.Result {
        try {
            return IndependentOcr(applicationContext).use { independent ->
                withIndependent(independent) {
                    val independentReadings by lazy { tesseractPage.read(independent) }
                    // The Paddle model loads only when a check below first reads with it.
                    PaddleOcr(applicationContext).use paddleRead@ { paddle ->
                        synchronized(lifecycle) { checkNotStopped(); activePaddle = paddle }
                        try {
                            val crossReader = CrossReaderVerifier(engine, paddle, independent)
                            // Keep the established successful routes before consulting
                            // additional rasters, which can introduce their own errors.
                            val regional = RegionalOcrVerifier(engine, { independentReadings }) { partial ->
                                !CrossReaderVerifier.canAttempt(partial) ||
                                    crossReader.closedBeforeCellReads(bitmap, partial, independentReadings)
                            }.verify(bitmap, first, second)
                            if (regional.unresolved == 0 || !CrossReaderVerifier.canAttempt(regional))
                                return@paddleRead regional
                            val cross = crossReader.verify(bitmap, regional,
                                independentReadings, File(preview.parentFile, "verification-%03d.json".format(index + 1)))
                            if (cross.unresolved == 0) return@paddleRead regional.copy(lines = cross.lines, unresolved = 0)
                            val pending = regional.copy(unresolved = cross.unresolved)
                            // Missing or ambiguously located consulted text cannot
                            // disappear when the next detector proposes other boxes.
                            if (cross.fallbackBlocked) return@paddleRead pending
                            val page = PaddleTextDetector(applicationContext, PaddleTextDetector.Profile.V6_SMALL).use { detector ->
                                synchronized(lifecycle) { checkNotStopped(); activeDetector = detector }
                                try {
                                    IndependentPageVerifier(engine, paddle, detector, independent).verify(bitmap,
                                        listOf(first, second), File(preview.parentFile, "page-verification-%03d.json".format(index + 1)))
                                } finally { synchronized(lifecycle) { if (activeDetector === detector) activeDetector = null } }
                            }
                            if (page.unresolved != 0) return@paddleRead pending.copy(unresolved = page.unresolved)
                            val conflicts = FallbackEvidenceGuard.inspect(cross.evidence, page.lines)
                            if (conflicts.isNotEmpty()) {
                                File(preview.parentFile, "fallback-evidence-%03d.json".format(index + 1)).writeText(
                                    JSONObject().put("direction", "legacy_to_independent")
                                        .put("candidate_lines", org.json.JSONArray(page.lines.map { line ->
                                            JSONObject().put("text", line.text).put("left", line.left.toDouble())
                                                .put("top", line.top.toDouble()).put("right", line.right.toDouble())
                                                .put("bottom", line.bottom.toDouble()).put("slope", line.slope.toDouble())
                                        })).put("conflicts", org.json.JSONArray(conflicts.map { conflict ->
                                            JSONObject().put("cells", org.json.JSONArray(conflict.cellIndices)).put("reason", conflict.reason)
                                        })).toString(2))
                                pending.copy(unresolved = conflicts.size)
                            } else {
                                warnings += "Las líneas se localizaron y contrastaron con modelos locales de familias distintas."
                                regional.copy(lines = page.lines, unresolved = 0, recovered = page.lines.size)
                            }
                        } finally { synchronized(lifecycle) { if (activePaddle === paddle) activePaddle = null } }
                    }
                }
            }
        } catch (error: InterruptedException) { throw error }
        catch (error: Exception) {
            saveVerificationError(preview, index, error)
            warnings += "No se pudo completar la comprobación óptica adicional."
        } catch (error: LinkageError) {
            saveVerificationError(preview, index, error)
            warnings += "El lector óptico adicional no está disponible en este dispositivo."
        }
        // Never erase a partial native observation by accepting another route.
        return RegionalOcrVerifier.Result(first.lines, 1, 0)
    }

    private fun savePreview(bitmap: Bitmap, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            temporary.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "No se pudo guardar la imagen de la página." }
            }
            if (!temporary.renameTo(destination)) throw IOException("No se pudo guardar la vista de la página.")
        } finally {
            temporary.delete()
        }
    }

    private fun saveVerificationError(preview: File, index: Int, error: Throwable) {
        if (stopped()) return
        try {
            // Private diagnostic only: no source image, OCR observations or
            // reference text. Avoid traversing causes/suppressed exceptions.
            val diagnostic = JSONObject()
                .put("type", error.javaClass.name.take(256))
                .put("message", error.message?.take(512) ?: JSONObject.NULL)
                .put("stack_trace", error.stackTrace.take(24)
                    .joinToString("\n") { it.toString().take(256) }.take(4096))
            File(preview.parentFile, "verification-error-%03d.json".format(index + 1))
                .writeText(diagnostic.toString(2))
        } catch (_: Exception) {
            // A diagnostic failure must not change OCR acceptance or status.
        } catch (_: LinkageError) {
            // Keep the same fallback even if a diagnostic dependency is absent.
        }
    }

    private fun failedPage(index: Int, preview: File, message: String) = ScanPage(
        index = index, imagePath = if (preview.isFile) preview.absolutePath else "",
        text = "", warnings = emptyList(),
        blockers = listOf("ERROR EN PÁGINA ${index + 1}: $message", "Vuelva a capturar esta página para generar el PDF."),
    )

    private fun checkNotStopped() {
        if (stopped()) throw InterruptedException("Escaneo interrumpido.")
    }

    private fun stopped(): Boolean = isStopped || cancellationRequested || Thread.currentThread().isInterrupted

    private fun readableError(error: Exception): String {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        return cause.message?.take(300)?.takeIf { it.isNotBlank() }
            ?: "No se pudo procesar esta página. Intente capturar una imagen más clara."
    }
}

package com.facturaclara.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.sqrt

/** Independent optical observations, never corrected or filtered by confidence. */
internal class IndependentOcr(context: Context) : Closeable {
    private val app = context.applicationContext
    private val lifecycle = Any()
    private val cancelled = AtomicBoolean(false)
    private var engine: TessBaseAPI? = null
    private var owner: Thread? = null
    private var recognizing = false
    private var closed = false

    /**
     * Two complete-page observations, PSM_SINGLE_COLUMN then PSM_SINGLE_BLOCK.
     * Positions refer to the caller's upright [original], normalized to 0..1.
     * Must run off the UI thread; concurrent calls on this instance are rejected.
     * The caller owns the original bitmap and must keep it alive until return.
     */
    @Throws(InterruptedException::class, IOException::class)
    fun recognize(original: Bitmap): List<List<OcrLine>> = recognizeOwned(original, region = false)

    /**
     * Only the [pass]-th observation of [recognize] (0 or 1), so that two readers can make
     * the complete-page passes side by side. Same preparation, settings and positions.
     */
    @Throws(InterruptedException::class, IOException::class)
    fun recognizePass(original: Bitmap, pass: Int): List<OcrLine> {
        require(pass in 0..1)
        return recognizeOwned(original, region = false, onlyPass = pass).single()
    }

    /**
     * Reads an already prepared crop with PSM_SINGLE_BLOCK and threshold methods 0 then 2.
     * No padding, scaling or pixel changes are applied here. Returned word positions are
     * normalized to [bitmap]; the caller maps them back through its crop transformation.
     * The caller retains ownership of [bitmap], including on failure or cancellation.
     */
    @Throws(InterruptedException::class, IOException::class)
    fun recognizeRegion(bitmap: Bitmap): List<List<OcrLine>> = recognizeOwned(bitmap, region = true)

    /** Two complete observations of a rectified text line, preserving all returned words. */
    @Throws(InterruptedException::class, IOException::class)
    fun recognizeLine(bitmap: Bitmap): List<List<OcrLine>> =
        recognizeOwned(bitmap, region = true, singleLine = true)

    private fun recognizeOwned(original: Bitmap, region: Boolean, singleLine: Boolean = false,
        onlyPass: Int? = null): List<List<OcrLine>> {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "El reconocimiento debe ejecutarse fuera del hilo de la interfaz."
        }
        synchronized(lifecycle) {
            check(!closed) { "El lector independiente está cerrado." }
            check(owner == null) { "El lector independiente ya está procesando una imagen." }
            checkpoint()
            owner = Thread.currentThread()
        }
        var prepared: Bitmap? = null
        try {
            require(!original.isRecycled) { "La imagen ya no está disponible." }
            val tess = initializedEngine()
            checkpoint()
            val input = if (region) original else prepare(original).also { prepared = it }
            val padding = if (region) 0 else PADDING
            val sx = input.width.toFloat() / (original.width + 2 * padding)
            val sy = input.height.toFloat() / (original.height + 2 * padding)
            val passes = if (singleLine) listOf(
                TessBaseAPI.PageSegMode.PSM_SINGLE_LINE to 0,
                TessBaseAPI.PageSegMode.PSM_RAW_LINE to 0,
            ) else if (region) listOf(
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to 0,
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to 2,
            ) else listOf(
                TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN to 0,
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to 0,
            )
            return (if (onlyPass == null) passes else listOf(passes[onlyPass])).map { (mode, threshold) ->
                observe(tess, input, mode, threshold, original.width, original.height, sx, sy, padding)
            }
                .also { checkpoint() }
        } finally {
            // Only this owner may clear or dispose of native state while a read is active.
            try {
                synchronized(lifecycle) {
                    recognizing = false
                    try { engine?.clear() }
                    finally {
                        owner = null
                        if (closed || cancelled.get()) disposeLocked()
                    }
                }
            } finally { prepared?.recycle() }
        }
    }

    /** Terminal cancellation; create another reader for subsequent work. Safe from any thread. */
    fun cancel() {
        cancelled.set(true)
        synchronized(lifecycle) {
            // stop() is the documented cross-thread exception to TessBaseAPI confinement.
            if (recognizing) engine?.stop()
            if (owner == null) disposeLocked()
        }
    }

    /** Nonblocking during recognition: cancel now, then the owner releases native resources. */
    override fun close() {
        cancelled.set(true)
        synchronized(lifecycle) {
            closed = true
            if (recognizing) engine?.stop()
            if (owner == null) disposeLocked()
        }
    }

    private fun disposeLocked() {
        val previous = engine
        engine = null
        previous?.recycle()
    }

    private fun checkpoint() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Lectura independiente interrumpida.")
        }
    }

    private fun initializedEngine(): TessBaseAPI {
        synchronized(lifecycle) { engine?.let { return it } }
        val directory = ensureModels()
        checkpoint()
        val created = TessBaseAPI { _ ->
            // Observe the owner's interruption even if a native callback changes threads.
            // This also closes the race between requesting stop and entering recognition.
            val interrupted = synchronized(lifecycle) { owner?.isInterrupted == true }
            if (cancelled.get() || interrupted) cancel()
        }
        synchronized(lifecycle) { engine = created }
        try {
            checkpoint()
            if (!created.init(directory.absolutePath, "eng+spa", TessBaseAPI.OEM_LSTM_ONLY,
                    mapOf("load_system_dawg" to "0", "load_freq_dawg" to "0"))) {
                throw IOException("No se pudo iniciar el segundo lector local.")
            }
            checkpoint()
            return created
        } catch (error: Throwable) {
            synchronized(lifecycle) { disposeLocked() }
            throw error
        }
    }

    private fun observe(tess: TessBaseAPI, bitmap: Bitmap, mode: Int, threshold: Int,
        originalWidth: Int, originalHeight: Int, sx: Float, sy: Float, padding: Int): List<OcrLine> {
        checkpoint()
        // The engine is reused across page and regional reads. clear() does not
        // reset parameters, so every pass must specify its thresholding method.
        if (!tess.setVariable("thresholding_method", threshold.toString())) {
            throw IOException("No se pudo configurar la lectura independiente de la imagen.")
        }
        tess.pageSegMode = mode
        tess.setImage(bitmap)
        synchronized(lifecycle) { checkpoint(); recognizing = true }
        try {
            // Unlike getUTF8Text alone, this operation supports stop() and progress.
            tess.getHOCRText(0)
        } finally { synchronized(lifecycle) { recognizing = false } }
        checkpoint()
        val iterator = tess.resultIterator ?: run { tess.clear(); return emptyList() }
        val words = mutableListOf<OcrLine>()
        try {
            iterator.begin()
            do {
                checkpoint()
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val word = iterator.getUTF8Text(level)
                // Tesseract visits non-text blocks as imaginary words with empty text.
                if (!word.isNullOrBlank()) {
                    val box = iterator.getBoundingRect(level)
                    val left = ((box.left / sx - padding) / originalWidth).coerceIn(0f, 1f)
                    val top = ((box.top / sy - padding) / originalHeight).coerceIn(0f, 1f)
                    val right = ((box.right / sx - padding) / originalWidth).coerceIn(0f, 1f)
                    val bottom = ((box.bottom / sy - padding) / originalHeight).coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) {
                        // Never present a partial observation after dropping a nonempty word.
                        throw IOException("El segundo lector devolvió texto sin una posición válida en el original.")
                    }
                    val score = iterator.confidence(level)
                    val confidence = if (score.isFinite() && score in 0f..100f) score / 100f else 0f
                    words += OcrLine(word, left, top, right, bottom, confidence = confidence)
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally { iterator.delete() }
        // Both passes share only the language models, not prior image/results buffers.
        tess.clear()
        return words
    }

    private fun ensureModels(): File {
        val directory = File(app.filesDir, "independent-ocr")
        MODEL_LOCK.lockInterruptibly()
        try {
            checkpoint()
            val data = File(directory, "tessdata")
            if (!data.isDirectory && !data.mkdirs()) throw IOException("No se pudieron preparar los modelos locales.")
            for ((name, expectedHash) in MODELS) {
                checkpoint()
                val file = AtomicFile(File(data, name))
                val existingHash = try {
                    file.openRead().use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        hex(digest.digest())
                    }
                } catch (_: FileNotFoundException) { null }
                if (existingHash == expectedHash) continue
                val stream = file.startWrite()
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    app.assets.open("tessdata/$name").use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    if (hex(digest.digest()) != expectedHash) throw IOException("El modelo local $name no supera la verificación de integridad.")
                    checkpoint()
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }
        } finally { MODEL_LOCK.unlock() }
        return directory
    }

    /** The probe's color page + 24px white border + bilinear 2x, capped at 4MP. */
    private fun prepare(source: Bitmap): Bitmap {
        val width = source.width + 2 * PADDING
        val height = source.height + 2 * PADDING
        val factor = minOf(2.0, sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height)))
        val outputWidth = (width * factor).toInt().coerceAtLeast(1)
        val outputHeight = (height * factor).toInt().coerceAtLeast(1)
        checkpoint()
        // Match the two-stage probe exactly for ordinary photos. For already oversized
        // sources, render directly into the capped raster to avoid an oversized copy.
        if (width.toLong() * height > MAX_PIXELS) {
            val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            try {
                val sx = outputWidth.toFloat() / width
                val sy = outputHeight.toFloat() / height
                Canvas(output).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, Rect(0, 0, source.width, source.height),
                        RectF(PADDING * sx, PADDING * sy, (width - PADDING) * sx, (height - PADDING) * sy),
                        Paint(Paint.FILTER_BITMAP_FLAG))
                }
                output.density = Bitmap.DENSITY_NONE
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
        }
        val padded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(padded).apply {
                drawColor(Color.WHITE)
                clipRect(PADDING, PADDING, source.width + PADDING, source.height + PADDING)
                drawBitmap(source, Rect(0, 0, source.width, source.height),
                    Rect(PADDING, PADDING, source.width + PADDING, source.height + PADDING), null)
            }
            checkpoint()
            val output = Bitmap.createScaledBitmap(padded, outputWidth, outputHeight, true)
            if (output !== padded) padded.recycle()
            output.density = Bitmap.DENSITY_NONE
            return output
        } catch (error: Throwable) { if (!padded.isRecycled) padded.recycle(); throw error }
    }

    companion object {
        private const val PADDING = 24
        private const val MAX_PIXELS = 4_000_000L
        private val MODEL_LOCK = ReentrantLock()
        // Official tessdata_best at e12c65a915945e4c28e237a9b52bc4a8f39a0cec.
        private val MODELS = linkedMapOf(
            "eng.traineddata" to "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba",
            "spa.traineddata" to "e2c1ffdad8b30f26c45d4017a9183d3a7f9aa69e59918be4f88b126fac99ab2c",
        )
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val alphabet = "0123456789abcdef"
            for (byte in bytes) { val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) }
        }
    }
}

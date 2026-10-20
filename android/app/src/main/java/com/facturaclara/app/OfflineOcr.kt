package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/** Uses the model packaged in the APK, without a Play Services model download. */
class OfflineOcr : Closeable {
    companion object {
        /** Below this shorter side, glyphs are too small for the recognizer at native size. */
        internal const val SMALL_PHOTO_SIDE = 700
        /** Upper bound for the enlarged copy, so a long, narrow photo is read at native size. */
        private const val MAX_ENLARGED_PIXELS = 8_000_000L
        /** Enlargements aim for about this shorter side, where receipt glyphs reach a readable size. */
        private const val TARGET_SHORT_SIDE = 1000.0

        /**
         * Whole-number enlargement for a small photo: 2x or 3x, whichever brings the shorter
         * side closest to [TARGET_SHORT_SIDE], stepping down to 2x when 3x would exceed
         * [MAX_ENLARGED_PIXELS]; 1 when the photo is large enough or even 2x does not fit.
         */
        internal fun enlargement(width: Int, height: Int): Int {
            val shorter = minOf(width, height)
            if (shorter <= 0 || shorter >= SMALL_PHOTO_SIDE) return 1
            val preferred = Math.round(TARGET_SHORT_SIDE / shorter).toInt().coerceIn(2, 3)
            return (preferred downTo 2).firstOrNull { width.toLong() * height * it * it <= MAX_ENLARGED_PIXELS } ?: 1
        }

        /** Exact second-pass preprocessing, shared with controlled image diagnostics. */
        /** Rotates on a white page, scaled down when the rotated bounds exceed [maxPixels]. */
        internal fun straighten(source: Bitmap, degrees: Float, maxPixels: Long): Pair<Bitmap, Float> {
            val radians = Math.toRadians(degrees.toDouble())
            val width = abs(source.width * kotlin.math.cos(radians)) + abs(source.height * kotlin.math.sin(radians))
            val height = abs(source.width * kotlin.math.sin(radians)) + abs(source.height * kotlin.math.cos(radians))
            val scale = minOf(1.0, kotlin.math.sqrt(maxPixels / (width * height))).toFloat()
            val matrix = android.graphics.Matrix().apply { setRotate(degrees); postScale(scale, scale) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val result = try {
                Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            } catch (error: Throwable) {
                if (rotated !== source) rotated.recycle()
                throw error
            }
            try {
                Canvas(result).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
                return result to scale
            } catch (error: Throwable) {
                result.recycle()
                throw error
            } finally {
                if (rotated !== source) rotated.recycle()
            }
        }

        internal fun contrastImage(source: Bitmap): Bitmap {
            val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            try {
                val grayscale = ColorMatrix().apply { setSaturation(0f) }
                val contrast = 1.18f
                val offset = 128f * (1f - contrast)
                grayscale.postConcat(ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(grayscale) }
                Canvas(result).drawBitmap(source, 0f, 0f, paint)
                return result
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
        }
    }

    data class Reading(
        val text: String,
        val warnings: List<String>,
        val blockers: List<String> = emptyList(),
        val suggestedRotation: Int = 0,
        val lines: List<OcrLine> = emptyList(),
        val captureBlockers: List<String> = emptyList(),
        val words: List<OcrLine> = emptyList(),
        /** Clockwise degrees that would straighten a clearly tilted page; 0 when not needed. */
        val suggestedDeskew: Float = 0f,
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Must run on a worker thread. The caller keeps [bitmap] alive until this returns. */
    @Throws(InterruptedException::class)
    fun recognize(bitmap: Bitmap): Reading = read(bitmap, false)

    /** A temporary crop inherits the original photograph's capture checks. */
    internal fun recognizeRegion(bitmap: Bitmap): Reading = read(bitmap, true)

    private fun read(bitmap: Bitmap, region: Boolean): Reading {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            "El reconocimiento debe ejecutarse fuera del hilo de la interfaz."
        }
        // Small photos are read from an enlargement; every capture check below keeps
        // describing the photo itself, and positions are normalized, so they are unchanged.
        val factor = if (region) 1 else enlargement(bitmap.width, bitmap.height)
        val input = if (factor == 1) bitmap else
            Bitmap.createScaledBitmap(bitmap, bitmap.width * factor, bitmap.height * factor, true)
        val width = input.width
        val height = input.height
        val task = recognizer.process(InputImage.fromBitmap(input, 0))
        val recognized = try {
            Tasks.await(task)
        } catch (interrupted: InterruptedException) {
            // The native task may still use the input; the worker must not recycle it yet.
            task.addOnCompleteListener(java.util.concurrent.Executor { command -> command.run() }) {
                if (input !== bitmap && !input.isRecycled) input.recycle()
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            throw interrupted
        } catch (error: Throwable) {
            // The task has finished with a failure; only the enlarged copy belongs to this call.
            if (input !== bitmap && !input.isRecycled) input.recycle()
            throw error
        }
        // Recognized geometry keeps only coordinates, never the pixels.
        if (input !== bitmap) input.recycle()
        val lines = recognized.textBlocks.flatMap { it.lines }.filter { it.text.isNotBlank() }
        val warnings = mutableListOf<String>()
        val blockers = mutableListOf<String>()
        if (!region && minOf(bitmap.width, bitmap.height) < 400) {
            blockers += "La imagen tiene muy poca resolución. Capture la factura más cerca y con buena luz."
        } else if (!region && minOf(bitmap.width, bitmap.height) < 700) {
            warnings += "La imagen tiene resolución limitada para el texto pequeño."
        }
        if (lines.isEmpty()) {
            blockers += "No se encontró texto legible. Vuelva a capturar la página con buena luz y enfoque."
            return Reading("", warnings, blockers)
        }
        val captureBlockers = blockers.toMutableList()
        val confidence = OcrConsistency.confidenceLevel(lines.map { it.confidence },
            lines.flatMap { it.elements }.filter { it.text.any(Char::isDigit) }.map { it.confidence })
        when (confidence) {
            OcrConsistency.ConfidenceLevel.SEVERE -> blockers +=
                "Parte del texto tiene una confianza muy baja o no válida. Tome otra foto con mejor enfoque y luz."
            OcrConsistency.ConfidenceLevel.CAUTION -> warnings +=
                "El motor asignó confianza moderada a parte del texto; la coincidencia entre lecturas no garantiza una transcripción exacta."
            OcrConsistency.ConfidenceLevel.CLEAR -> Unit
        }
        val smallLines = lines.count { (it.boundingBox?.height() ?: Int.MAX_VALUE) < 14 * factor }
        if (!region && smallLines > lines.size / 5) {
            captureBlockers += "El texto es demasiado pequeño para una lectura fiable. Acerque la cámara sin cortar la factura."
        } else if (smallLines > 0) {
            warnings += "Parte del texto impreso es muy pequeño."
        }
        val rows = arrangeRows(lines)
        if (rows.count { it.size > 1 } >= 2) {
            warnings += "La disposición de las columnas se reconstruyó a partir de la posición del texto."
        }
        if (!region && formatRows(rows).length < 20) {
            captureBlockers += "Se detectó muy poco texto. Capture la factura completa y enfocada."
        }
        val rotation = OcrOrientation.quarterTurn(lines.map { it.angle })
        // Small photos lose more to resampling than they gain from straightening, and
        // their resolution checks must keep describing the photo itself.
        val deskew = if (region || rotation != 0 || minOf(bitmap.width, bitmap.height) < 700) 0f else
            OcrOrientation.deskew(lines.filter { it.text.length >= 3 && it.confidence >= .5f }.map { it.angle })
        if (!region && rotation == 0 && lines.count { abs(it.angle) > 10f } > lines.size / 3) {
            captureBlockers += "Parte del texto está inclinado o girado. Capture la página de frente."
        }
        val geometry = rows.flatten().map { line -> positionedFragments(line, width, height) }
        val positioned = if (geometry.any { it.isEmpty() }) {
            // Partial geometry must not make the PDF silently omit unpositioned text.
            warnings += "No se obtuvo la posición completa del texto; se conserva la transcripción completa."
            emptyList()
        } else geometry.flatten()
        val text = if (positioned.isNotEmpty()) OcrLayout.text(positioned) else formatRows(rows)
        return Reading(text, warnings.distinct(), (blockers + captureBlockers).distinct(), rotation, positioned,
            captureBlockers.distinct(), lines.flatMap { line -> line.elements.mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                val left = (box.left.toFloat() / width).coerceIn(0f, 1f)
                val right = (box.right.toFloat() / width).coerceIn(0f, 1f)
                val top = (box.top.toFloat() / height).coerceIn(0f, 1f)
                val bottom = (box.bottom.toFloat() / height).coerceIn(0f, 1f)
                if (right <= left || bottom <= top || element.text.isBlank()) return@mapNotNull null
                val slope = if (element.angle.isFinite() && abs(element.angle) <= 30f)
                    (tan(Math.toRadians(element.angle.toDouble())) * width / height).toFloat() else 0f
                OcrLine(element.text, left, top, right, bottom, slope,
                    element.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
            } }, suggestedDeskew = deskew)
    }

    private fun positionedFragments(line: Text.Line, width: Int, height: Int): List<OcrLine> {
        val lineBox = line.boundingBox ?: return emptyList()
        fun fragment(text: String, box: Rect, elements: List<Text.Element> = line.elements,
            includeLineConfidence: Boolean = true): OcrLine? {
            val left = (box.left.toFloat() / width).coerceIn(0f, 1f)
            val top = (box.top.toFloat() / height).coerceIn(0f, 1f)
            val right = (box.right.toFloat() / width).coerceIn(0f, 1f)
            val bottom = (box.bottom.toFloat() / height).coerceIn(0f, 1f)
            // Project neighboring columns onto the same printed baseline. Axis-aligned
            // boxes alone can associate a tilted product with the preceding price.
            val slope = if (line.angle.isFinite() && abs(line.angle) <= 30f) {
                (tan(Math.toRadians(line.angle.toDouble())) * width / height).toFloat()
            } else 0f
            val relevant = elements.map { it.confidence } +
                if (includeLineConfidence) listOf(line.confidence) else emptyList()
            val confidence = relevant.minOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            return if (right > left && bottom > top) OcrLine(text, left, top, right, bottom, slope, confidence) else null
        }
        fun wholeLine(): List<OcrLine> = listOfNotNull(fragment(line.text, lineBox))
        val elements = line.elements
        if (elements.size < 2 || elements.any { it.boundingBox == null }) return wholeLine()
        val widths = elements.map { it.boundingBox!!.width().toFloat() / it.text.length.coerceAtLeast(1) }
            .filter { it > 0 }.sorted()
        val heights = elements.map { it.boundingBox!!.height().toFloat() }.filter { it > 0 }.sorted()
        if (widths.isEmpty() || heights.isEmpty()) return wholeLine()
        val gapThreshold = maxOf(widths[widths.size / 2] * 2.25f, heights[heights.size / 2] * .9f)
        val groups = mutableListOf<MutableList<Text.Element>>()
        elements.forEach { element ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous == null || element.boundingBox!!.left - previous.boundingBox!!.right > gapThreshold) {
                groups += mutableListOf(element)
            } else groups.last() += element
        }
        if (groups.size == 1) return wholeLine()
        val fragments = groups.mapNotNull { group ->
            val box = Rect(group.first().boundingBox!!)
            group.drop(1).forEach { box.union(it.boundingBox!!) }
            fragment(group.joinToString(" ") { it.text }, box, group, false)
        }
        val reconstructed = fragments.joinToString(" ") { it.text }
        return if (fragments.size == groups.size && OcrConsistency.compare(line.text, reconstructed).contentMatches) {
            fragments
        } else wholeLine()
    }

    private fun arrangeRows(lines: List<Text.Line>): List<List<Text.Line>> {
        val sorted = lines.sortedWith(compareBy<Text.Line> { it.boundingBox?.exactCenterY() ?: Float.MAX_VALUE }
            .thenBy { it.boundingBox?.left ?: Int.MAX_VALUE })
        val rows = mutableListOf<MutableList<Text.Line>>()
        sorted.forEach { line ->
            val box = line.boundingBox
            val last = rows.lastOrNull()
            val reference = last?.firstOrNull()?.boundingBox
            if (last != null && box != null && reference != null && sameRow(box, reference)) {
                last.add(line)
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.map { row -> row.sortedBy { it.boundingBox?.left ?: Int.MAX_VALUE } }
    }

    private fun sameRow(first: Rect, second: Rect): Boolean =
        abs(first.exactCenterY() - second.exactCenterY()) <= minOf(first.height(), second.height()) * 0.55f

    private fun formatRows(rows: List<List<Text.Line>>): String {
        val characterWidths = rows.flatten().mapNotNull { line ->
            line.boundingBox?.let { it.width().toFloat() / line.text.length.coerceAtLeast(1) }
        }.filter { it > 0 }.sorted()
        val characterWidth = characterWidths.getOrNull(characterWidths.size / 2) ?: 8f
        return rows.joinToString("\n") { row ->
            buildString {
                var previous: Rect? = null
                row.forEach { line ->
                    val box = line.boundingBox
                    if (isNotEmpty()) {
                        val gap = if (previous != null && box != null) box.left - previous!!.right else 0
                        append(" ".repeat((gap / characterWidth).roundToInt().coerceIn(2, 60)))
                    }
                    append(line.text.trim())
                    previous = box
                }
            }
        }
    }

    override fun close() {
        recognizer.close()
    }
}

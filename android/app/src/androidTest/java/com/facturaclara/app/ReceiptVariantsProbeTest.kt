package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Diagnostic variants only. Never feeds expected text into OCR or changes an imported document. */
@RunWith(AndroidJUnit4::class)
class ReceiptVariantsProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun compareTemporaryPixelVariantsOfUntouchedReceiptPhotos() {
        val runName = InstrumentationRegistry.getArguments().getString("probeRun")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "variants-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "receipt-variants/$runName")
        check(directory.mkdirs()) { "Use a new probeRun; diagnostic results are never overwritten." }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val selectedSamples = InstrumentationRegistry.getArguments().getString("sampleSelection")?.split(',')?.toSet()
        val selectedVariants = InstrumentationRegistry.getArguments().getString("variantSelection")?.split(',')?.toSet()
        val samples = JSONArray()
        val report = JSONObject()
            .put("scope", "Diagnostic pixel variants; no production worker, PDF generation or acceptance decision.")
            .put("accuracy_claim", JSONObject.NULL)
            .put("reference_text_used_by_recognizer", false)
            .put("originals_modified", false)
            .put("variants_max_pixels", MAX_VARIANT_PIXELS)
            .put("local_illumination_algorithm", "Grayscale pixel * 220 / max(30, neighborhood mean); radius max(24, min(width,height)/12); clipped to 0..255, then enlarged.")
            .put("samples", samples)
        try {
            OfflineOcr().use { engine ->
                // allSamples=true covers every bundled test photo instead of the original five.
                val files = if (InstrumentationRegistry.getArguments().getString("allSamples") == "true")
                    instrumentation.context.assets.list("real-receipt-photos").orEmpty().filter { it.endsWith(".jpg") }.sorted()
                else SAMPLE_FILES
                files.filter { selectedSamples == null || it.take(2) in selectedSamples }.forEach { filename ->
                    val sourceBytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                    val originalHash = sha256(sourceBytes)
                    val original = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
                        ?: error("Unable to decode $filename")
                    original.density = Bitmap.DENSITY_NONE
                    val variants = JSONArray()
                    val sample = JSONObject().put("filename", filename).put("source_sha256", originalHash)
                        .put("source_width", original.width).put("source_height", original.height)
                        .put("variants", variants)
                    samples.put(sample)
                    try {
                        val initialRaw = Tasks.await(recognizer.process(InputImage.fromBitmap(original, 0)))
                        val rotation = robustRotation(initialRaw.textBlocks.flatMap { it.lines })
                        sample.put("automatic_rotation", rotation.json())
                        val preparations = listOf<Pair<String, () -> Bitmap>>(
                            "original" to { original },
                            "scale_2x" to { enlarge(original) },
                            "grayscale_2x" to { transformedAndEnlarged(original, ::grayscale) },
                            "contrast_1_18_2x" to { transformedAndEnlarged(original, OfflineOcr::contrastImage) },
                            "automatic_rotation_2x" to { transformedAndEnlarged(original) { rotatedWhite(it, rotation.degrees) } },
                            "automatic_rotation_native" to { rotatedWhite(original, rotation.degrees) },
                            "local_illumination_2x" to { transformedAndEnlarged(original, ::normalizeIllumination) },
                            "contrast_1_18_native" to { OfflineOcr.contrastImage(original) },
                        ).filter { selectedVariants == null || it.first in selectedVariants }
                        check(preparations.isNotEmpty()) { "No requested variants exist." }
                        preparations.forEach { (name, prepare) ->
                            val start = SystemClock.elapsedRealtime()
                            var bitmap: Bitmap? = null
                            try {
                                bitmap = prepare()
                                val preparedAt = SystemClock.elapsedRealtime()
                                val raw = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                                val rawAt = SystemClock.elapsedRealtime()
                                val reading = engine.recognize(bitmap)
                                val finishedAt = SystemClock.elapsedRealtime()
                                val rawLines = raw.textBlocks.flatMap { it.lines }
                                val values = rawLines.map { it.confidence }
                                val numericValues = rawLines.flatMap { it.elements }.filter { it.text.any(Char::isDigit) }.map { it.confidence }
                                variants.put(JSONObject().put("variant", name)
                                    .put("width", bitmap.width).put("height", bitmap.height)
                                    .put("preparation_ms", preparedAt - start).put("raw_ocr_ms", rawAt - preparedAt)
                                    .put("offline_ocr_ms", finishedAt - rawAt).put("total_ms", finishedAt - start)
                                    .put("raw_text", raw.text).put("text", reading.text)
                                    .put("warnings", JSONArray(reading.warnings)).put("blockers", JSONArray(reading.blockers))
                                    .put("suggested_rotation", reading.suggestedRotation)
                                    .put("confidence_level", OcrConsistency.confidenceLevel(values, numericValues).name)
                                    .put("min_line_confidence", finiteOrNull(values.minOrNull()))
                                    .put("mean_line_confidence", finiteOrNull(values.takeIf { it.isNotEmpty() }?.average()?.toFloat()))
                                    .put("min_numeric_confidence", finiteOrNull(numericValues.minOrNull()))
                                    .put("angles", JSONArray(rawLines.map { finiteOrNull(it.angle) }))
                                    .put("raw_lines", JSONArray(rawLines.map(::rawLineJson)))
                                    .put("positioned_lines", JSONArray(reading.lines.map { line ->
                                        JSONObject().put("text", line.text).put("left", line.left).put("top", line.top)
                                            .put("right", line.right).put("bottom", line.bottom).put("slope", line.slope).put("confidence", line.confidence?.toDouble())
                                    })))
                            } catch (error: Exception) {
                                variants.put(JSONObject().put("variant", name).put("error", error.toString())
                                    .put("total_ms", SystemClock.elapsedRealtime() - start))
                            } finally {
                                if (bitmap !== original && bitmap?.isRecycled == false) bitmap.recycle()
                            }
                            File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
                        }
                        val afterBytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                        assertEquals("The published input must remain unchanged", originalHash, sha256(afterBytes))
                        sample.put("source_hash_after", sha256(afterBytes))
                        assertEquals(preparations.size, variants.length())
                        assertTrue("At least the original OCR should complete for $filename", !variants.getJSONObject(0).has("error"))
                    } finally {
                        original.recycle()
                        File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    @Test fun compareAutomaticFullWidthRowBandsWithoutExpectedText() {
        val runName = InstrumentationRegistry.getArguments().getString("probeRun")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "rows-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "receipt-row-variants/$runName")
        check(directory.mkdirs()) { "Use a new probeRun; diagnostic results are never overwritten." }
        val samples = JSONArray()
        val report = JSONObject().put("scope", "Automatic full-width OCR row bands; diagnostic only, never merged into a document.")
            .put("accuracy_claim", JSONObject.NULL).put("reference_text_used_by_recognizer", false)
            .put("originals_modified", false).put("samples", samples)
            .put("geometry_rule", "Rows from full-image OCR bounding boxes; full image width; 0.75 median row height vertical margin, minimum 8 source pixels; white border 24 pixels before enlargement.")
            .put("warning", "A crop may include neighboring rows. Crop-level size blockers do not evaluate the original photograph. No crop output is silently joined or deduplicated.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            OfflineOcr().use { engine ->
                SAMPLE_FILES.take(2).forEach { filename ->
                    val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unable to decode $filename")
                    original.density = Bitmap.DENSITY_NONE
                    val sourceHash = sha256(bytes)
                    val bands = JSONArray()
                    val sample = JSONObject().put("filename", filename).put("source_sha256", sourceHash)
                        .put("source_width", original.width).put("source_height", original.height).put("bands", bands)
                    samples.put(sample)
                    try {
                        val raw = Tasks.await(recognizer.process(InputImage.fromBitmap(original, 0)))
                        val rawLines = raw.textBlocks.flatMap { it.lines }
                        val positioned = rawLines.mapNotNull { line ->
                            line.boundingBox?.let { box ->
                                val left = box.left.toFloat().coerceIn(0f, original.width.toFloat()) / original.width
                                val top = box.top.toFloat().coerceIn(0f, original.height.toFloat()) / original.height
                                val right = box.right.toFloat().coerceIn(0f, original.width.toFloat()) / original.width
                                val bottom = box.bottom.toFloat().coerceIn(0f, original.height.toFloat()) / original.height
                                if (left < right && top < bottom) OcrLine(line.text, left, top, right, bottom) else null
                            }
                        }
                        val rows = OcrLayout.rows(positioned)
                        sample.put("raw_lines", JSONArray(rawLines.map(::rawLineJson)))
                            .put("positioned_fragment_count", positioned.size).put("row_count", rows.size)
                            .put("rows", JSONArray(rows.map { row -> JSONArray(row.map { line ->
                                JSONObject().put("text", line.text).put("left", line.left).put("top", line.top)
                                    .put("right", line.right).put("bottom", line.bottom)
                            }) }))
                        assertEquals("Every geometric fragment must survive row grouping", positioned.size, rows.sumOf { it.size })
                        rows.indices.forEach { targetIndex ->
                            for (contextRows in listOf(1, 3)) {
                                val first = (targetIndex - contextRows / 2).coerceAtLeast(0)
                                val last = (targetIndex + contextRows / 2).coerceAtMost(rows.lastIndex)
                                val included = rows.subList(first, last + 1).flatten()
                                val heights = included.map { (it.bottom - it.top) * original.height }.sorted()
                                val margin = maxOf(8f, heights[heights.size / 2] * .75f)
                                val top = (included.minOf { it.top } * original.height - margin).toInt().coerceAtLeast(0)
                                val bottom = (included.maxOf { it.bottom } * original.height + margin).roundToInt().coerceAtMost(original.height)
                                val band = JSONObject().put("target_row", targetIndex).put("context_rows", contextRows)
                                    .put("included_row_indices", JSONArray((first..last).toList()))
                                    .put("source_bounds", JSONArray(listOf(0, top, original.width, bottom)))
                                    .put("detected_target_fragments", JSONArray(rows[targetIndex].map { it.text }))
                                val variants = JSONArray()
                                band.put("variants", variants)
                                bands.put(band)
                                val stripe = Bitmap.createBitmap(original, 0, top, original.width, bottom - top)
                                val padded = Bitmap.createBitmap(stripe.width + 48, stripe.height + 48, Bitmap.Config.ARGB_8888)
                                Canvas(padded).apply { drawColor(Color.WHITE); drawBitmap(stripe, 24f, 24f, null) }
                                if (stripe !== original) stripe.recycle()
                                val choices = if (contextRows == 1) listOf("color_2x", "color_4x", "gray_2x", "contrast_2x")
                                    else listOf("color_2x", "contrast_2x")
                                try {
                                    choices.forEach { name ->
                                        val start = SystemClock.elapsedRealtime()
                                        val variant = when (name) {
                                            "gray_2x" -> transformedAndEnlarged(padded, ::grayscale)
                                            "contrast_2x" -> transformedAndEnlarged(padded, OfflineOcr::contrastImage)
                                            "color_4x" -> enlarge(padded, 4.0)
                                            else -> enlarge(padded)
                                        }
                                        try {
                                            val recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(variant, 0)))
                                            val reading = engine.recognize(variant)
                                            variants.put(JSONObject().put("variant", name).put("width", variant.width).put("height", variant.height)
                                                .put("total_ms", SystemClock.elapsedRealtime() - start).put("raw_text", recognized.text)
                                                .put("text", reading.text).put("blockers", JSONArray(reading.blockers))
                                                .put("warnings", JSONArray(reading.warnings))
                                                .put("raw_lines", JSONArray(recognized.textBlocks.flatMap { it.lines }.map(::rawLineJson))))
                                        } finally {
                                            if (variant !== padded) variant.recycle()
                                        }
                                    }
                                } finally {
                                    padded.recycle()
                                }
                                File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
                            }
                        }
                        val after = instrumentation.context.assets.open("real-receipt-photos/$filename").use { sha256(it.readBytes()) }
                        sample.put("source_hash_after", after)
                        assertEquals(sourceHash, after)
                    } finally {
                        original.recycle()
                        File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
                    }
                }
            }
        } finally {
            recognizer.close()
        }
    }

    private data class Rotation(val degrees: Float, val support: Int, val lineCount: Int, val reason: String) {
        fun json() = JSONObject().put("degrees_applied", degrees).put("support", support)
            .put("line_count", lineCount).put("reason", reason)
    }

    private fun robustRotation(lines: List<Text.Line>): Rotation {
        val angles = lines.filter { it.text.length >= 3 && it.confidence >= .50f && it.angle.isFinite() }.map { it.angle }
        if (angles.size < 3) return Rotation(0f, 0, angles.size, "Too few usable lines; original orientation retained.")
        // Normalize quarter-turn bins so +180 and -180 count as the same orientation.
        val grouped = angles.groupBy { (((it / 90f).roundToInt() % 4) + 4) % 4 }
        val dominant = grouped.maxByOrNull { it.value.size }!!
        val center = dominant.key * 90f
        fun circularDifference(value: Float): Float = ((value - center + 540f) % 360f) - 180f
        val residuals = dominant.value.map(::circularDifference).sorted()
        val median = residuals[residuals.size / 2]
        val inliers = residuals.filter { abs(it - median) <= 8f }.sorted()
        if (inliers.size < 3 || inliers.size * 5 < angles.size * 3 || abs(median) > 25f) {
            return Rotation(0f, inliers.size, angles.size, "No sufficiently consistent dominant angle; original orientation retained.")
        }
        val angle = center + inliers[inliers.size / 2]
        val correction = ((-angle + 540f) % 360f) - 180f
        return Rotation(if (abs(correction) < .30f) 0f else correction, inliers.size, angles.size,
            "Circular quarter-turn consensus, then median residual; no manually selected angle.")
    }

    private fun enlarge(source: Bitmap, requestedFactor: Double = 2.0): Bitmap {
        val factor = minOf(requestedFactor, sqrt(MAX_VARIANT_PIXELS.toDouble() / (source.width.toLong() * source.height)))
        return Bitmap.createScaledBitmap(source, (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1), true).apply { density = Bitmap.DENSITY_NONE }
    }

    private fun transformedAndEnlarged(source: Bitmap, transform: (Bitmap) -> Bitmap): Bitmap {
        val transformed = transform(source)
        var enlarged: Bitmap? = null
        try {
            enlarged = enlarge(transformed)
            return enlarged
        } finally {
            if (transformed !== source && transformed !== enlarged) transformed.recycle()
        }
    }

    private fun grayscale(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun rotatedWhite(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { setRotate(degrees) }, true)
        val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(result).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
            return result
        } finally {
            if (rotated !== source) rotated.recycle()
        }
    }

    private fun normalizeIllumination(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val stride = width + 1
        val integral = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val gray = (Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114).roundToInt()
                pixels[y * width + x] = gray
                rowSum += gray
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
        val radius = maxOf(24, minOf(width, height) / 12)
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius + 1).coerceAtMost(height)
            for (x in 0 until width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius + 1).coerceAtMost(width)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val mean = sum.toDouble() / ((x1 - x0) * (y1 - y0))
                val corrected = (pixels[y * width + x] * 220.0 / mean.coerceAtLeast(30.0)).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = Color.rgb(corrected, corrected, corrected)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rawLineJson(line: Text.Line): JSONObject = JSONObject().put("text", line.text)
        .put("confidence", finiteOrNull(line.confidence)).put("angle", finiteOrNull(line.angle))
        .put("bounding_box", line.boundingBox?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
        .put("corner_points", line.cornerPoints?.let { JSONArray(it.map { point -> JSONArray(listOf(point.x, point.y)) }) } ?: JSONObject.NULL)
        .put("elements", JSONArray(line.elements.map { element ->
            JSONObject().put("text", element.text).put("confidence", finiteOrNull(element.confidence))
                .put("angle", finiteOrNull(element.angle))
                .put("bounding_box", element.boundingBox?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
                .put("corner_points", element.cornerPoints?.let { JSONArray(it.map { point -> JSONArray(listOf(point.x, point.y)) }) } ?: JSONObject.NULL)
        }))

    private fun finiteOrNull(value: Float?): Any = value?.takeIf { it.isFinite() } ?: JSONObject.NULL

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val MAX_VARIANT_PIXELS = 4_000_000L
        private val SAMPLE_FILES = listOf("02_desenfoque_suave.jpg", "08_pliegues_y_contraste.jpg",
            "11_papel_curvado.jpg", "15_sombra_del_telefono.jpg", "17_foto_girada.jpg")
    }
}

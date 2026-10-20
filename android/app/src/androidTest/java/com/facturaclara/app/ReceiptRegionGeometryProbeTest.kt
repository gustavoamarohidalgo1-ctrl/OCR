package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * New optical observations only: region deskew, 2x/3x, two/three-row context and
 * horizontal crop. Source bytes remain untouched. No reference text, PDF export,
 * Repository document, or acceptance decision is created by this diagnostic.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptRegionGeometryProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun inspectNewRegionalObservationsAndOwnershipWithoutCorrectingText() {
        val args = InstrumentationRegistry.getArguments()
        val runName = args.getString("probeRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
            ?: "geometry-${System.currentTimeMillis()}"
        val selection = args.getString("sampleSelection")?.split(',')?.toSet()
        val variantSelection = args.getString("variantSelection")?.split(',')?.toSet()
        val maxAnchors = args.getString("maxAnchors")?.toIntOrNull()?.coerceIn(1, 80) ?: 16
        val directory = File(context.getExternalFilesDir(null), "receipt-region-geometry/$runName")
        check(directory.mkdirs()) { "Use a new probeRun; evidence is never overwritten." }
        val samples = JSONArray()
        val report = JSONObject().put("scope", "Diagnostic only; no source edits, reference text, production documents or PDFs.")
            .put("originals_modified", false).put("reference_text_used", false)
            .put("accuracy_claim", JSONObject.NULL).put("max_anchors_per_sample", maxAnchors)
            .put("anchor_rule", "Production row grouping of native and contrast observations. Probe unresolved whole-row consensus and rows with angle disagreement; preserve every observation before any owner filtering.")
            .put("deskew_rule", "Negative weighted median of overlapping native raw line angles. Weight = horizontal width squared; usable angles must be finite and within 15 degrees. No known receipt text is consulted.")
            .put("samples", samples)
        fun persist() = File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            OfflineOcr().use { engine ->
                SAMPLE_FILES.filter { selection == null || it.take(2) in selection }.forEach { filename ->
                    val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                    val native = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Cannot decode $filename")
                    native.density = Bitmap.DENSITY_NONE
                    val exif = ExifInterface(ByteArrayInputStream(bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val bitmap = orient(native, exif)
                    if (bitmap !== native) native.recycle()
                    val sourceHash = sha256(bytes)
                    val sample = JSONObject().put("filename", filename).put("source_sha256", sourceHash)
                        .put("exif_orientation", exif).put("upright_width", bitmap.width).put("upright_height", bitmap.height)
                    samples.put(sample)
                    try {
                        val raw = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        val rawLines = raw.textBlocks.flatMap { it.lines }
                        val first = engine.recognize(bitmap)
                        val contrast = OfflineOcr.contrastImage(bitmap)
                        val second = try { engine.recognize(contrast) } finally { contrast.recycle() }
                        val full2x = prepare(bitmap, RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), 0f, 2.0, true, 0)
                        val third = try { engine.recognizeRegion(full2x.bitmap) } finally { full2x.bitmap.recycle() }
                        val readings = listOf(first.lines, second.lines, third.lines)
                        val anchors = OcrLayout.rows(first.lines).map { it.toMutableList() }.toMutableList()
                        for (reading in readings.drop(1)) for (row in OcrLayout.rows(reading)) {
                            if (row.none { owner(it, anchors) >= 0 }) anchors += row.toMutableList()
                        }
                        anchors.sortBy { row -> row.map { (it.top + it.bottom) / 2 }.average() }
                        val assigned = readings.map { reading -> anchors.indices.map { index -> reading.filter { owner(it, anchors) == index } } }
                        val anchorData = JSONArray()
                        val selected = mutableListOf<Int>()
                        for (index in anchors.indices) {
                            val observations = assigned.map { it[index] }
                            val stable = RegionConsensus.whole(observations)
                            val geometryRisk = anchors[index].let { row ->
                                row.size > 1 && row.maxOf { it.slope } - row.minOf { it.slope } > .05f
                            }
                            val uncertain = stable == null || geometryRisk
                            if (uncertain) selected += index
                            anchorData.put(JSONObject().put("index", index).put("stable_whole_consensus", stable != null)
                                .put("cross_column_angle_disagreement", geometryRisk).put("probe_candidate", uncertain)
                                .put("anchor_lines", JSONArray(anchors[index].map(::positionedJson)))
                                .put("whole_observations", JSONArray(observations.map { JSONArray(it.map(::positionedJson)) })))
                        }
                        sample.put("native_raw_lines", JSONArray(rawLines.map(::rawLineJson)))
                            .put("whole_readings", JSONArray(listOf(first, second, third).map { readingJson(it) }))
                            .put("anchors", anchorData).put("candidate_anchor_count", selected.size)
                            .put("probed_anchor_indices", JSONArray(selected.take(maxAnchors)))
                        val probes = JSONArray()
                        sample.put("probes", probes)
                        persist()
                        for (target in selected.take(maxAnchors)) {
                            val variants = JSONArray()
                            val probe = JSONObject().put("target_anchor", target).put("variants", variants)
                            probes.put(probe)
                            for (spec in VARIANTS.filter { variantSelection == null || it.name in variantSelection }) {
                                val started = SystemClock.elapsedRealtime()
                                val firstIndex = (target - (spec.contextRows - 1) / 2).coerceAtLeast(0)
                                val lastIndex = (firstIndex + spec.contextRows - 1).coerceAtMost(anchors.lastIndex)
                                val included = anchors.subList(firstIndex, lastIndex + 1).flatten()
                                val heights = included.map { (it.bottom - it.top) * bitmap.height }.sorted()
                                val medianHeight = heights[heights.size / 2]
                                val verticalMargin = maxOf(8f, medianHeight * .75f)
                                val horizontalMargin = maxOf(12f, medianHeight * 1.5f)
                                val rect = RectF(
                                    if (spec.tight) floor(included.minOf { it.left } * bitmap.width - horizontalMargin).coerceAtLeast(0f) else 0f,
                                    floor(included.minOf { it.top } * bitmap.height - verticalMargin).coerceAtLeast(0f),
                                    if (spec.tight) ceil(included.maxOf { it.right } * bitmap.width + horizontalMargin).coerceAtMost(bitmap.width.toFloat()) else bitmap.width.toFloat(),
                                    ceil(included.maxOf { it.bottom } * bitmap.height + verticalMargin).coerceAtMost(bitmap.height.toFloat()),
                                )
                                val angles = angleEvidence(rawLines, rect)
                                val correction = if (spec.deskew) -weightedMedian(angles) else 0f
                                val variant = JSONObject().put("name", spec.name)
                                    .put("included_anchor_indices", JSONArray((firstIndex..lastIndex).toList()))
                                    .put("source_rectangle", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                                    .put("angle_evidence", JSONArray(angles.map { JSONObject().put("angle", it.first).put("width_squared_weight", it.second) }))
                                    .put("deskew_degrees", correction).put("requested_scale", spec.scale)
                                variants.put(variant)
                                var prepared: Prepared? = null
                                try {
                                    prepared = prepare(bitmap, rect, correction, spec.scale, spec.contrast, PADDING)
                                    val rawResult = Tasks.await(recognizer.process(InputImage.fromBitmap(prepared.bitmap, 0)))
                                    val reading = engine.recognizeRegion(prepared.bitmap)
                                    val mapped = reading.lines.mapNotNull { mapToSource(it, prepared, bitmap.width, bitmap.height) }
                                    variant.put("width", prepared.bitmap.width).put("height", prepared.bitmap.height)
                                        .put("effective_scale", prepared.scale).put("source_to_variant_matrix", matrixJson(prepared.forward))
                                        .put("variant_to_source_matrix", matrixJson(prepared.inverse))
                                        .put("raw_text", rawResult.text).put("raw_lines", JSONArray(rawResult.textBlocks.flatMap { it.lines }.map(::rawLineJson)))
                                        .put("reading", readingJson(reading))
                                        .put("mapped_observations", JSONArray(mapped.map { line -> positionedJson(line)
                                            .put("owner", owner(line, anchors)).put("all_owner_distances", JSONArray(anchors.map { OcrLayout.rowDistance(line, it) })) }))
                                        .put("target_owned_text", OcrLayout.text(mapped.filter { owner(it, anchors) == target }))
                                        .put("target_owned_reliable", RegionConsensus.reliable(mapped.filter { owner(it, anchors) == target }))
                                } catch (error: Exception) {
                                    variant.put("error", error.toString())
                                } finally {
                                    prepared?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                                    variant.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                                    persist()
                                }
                            }
                            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$filename anchor $target (${probes.length()}/${selected.take(maxAnchors).size})\n") })
                        }
                        val afterHash = instrumentation.context.assets.open("real-receipt-photos/$filename").use { sha256(it.readBytes()) }
                        sample.put("source_sha256_after", afterHash)
                        assertEquals("Original assets must be byte-identical", sourceHash, afterHash)
                    } finally {
                        bitmap.recycle()
                        persist()
                    }
                }
            }
        } finally { recognizer.close() }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nREGION_GEOMETRY_EVIDENCE=${directory.absolutePath}\n") })
    }

    private fun owner(line: OcrLine, anchors: List<List<OcrLine>>): Int {
        val closest = anchors.indices.minByOrNull { OcrLayout.rowDistance(line, anchors[it]) } ?: return -1
        val heights = anchors[closest].map { it.bottom - it.top }.sorted()
        return if (OcrLayout.rowDistance(line, anchors[closest]) <= maxOf(line.bottom - line.top, heights[heights.size / 2]) * .9f) closest else -1
    }

    internal fun angleEvidence(lines: List<Text.Line>, rect: RectF): List<Pair<Float, Double>> = lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        val overlap = maxOf(0f, minOf(rect.bottom, box.bottom.toFloat()) - maxOf(rect.top, box.top.toFloat()))
        val horizontalOverlap = maxOf(0f, minOf(rect.right, box.right.toFloat()) - maxOf(rect.left, box.left.toFloat()))
        if (overlap < box.height() * .5f || horizontalOverlap < box.width() * .5f || line.text.length < 4 || !line.angle.isFinite() || abs(line.angle) > 15f) null
        else line.angle to box.width().toDouble() * box.width()
    }

    internal fun weightedMedian(values: List<Pair<Float, Double>>): Float {
        if (values.isEmpty()) return 0f
        val half = values.sumOf { it.second } / 2
        var weight = 0.0
        return values.sortedBy { it.first }.first { weight += it.second; weight >= half }.first
    }

    // Shared test-only raster helpers allow independent engines to see the same
    // temporary pixels and coordinate mapping, without production dependencies.
    internal data class Prepared(val bitmap: Bitmap, val forward: Matrix, val inverse: Matrix, val scale: Double)

    internal fun prepare(source: Bitmap, rect: RectF, degrees: Float, requestedScale: Double, contrast: Boolean, padding: Int): Prepared {
        val padded = RectF(0f, 0f, rect.width() + 2 * padding, rect.height() + 2 * padding)
        val rotated = RectF(padded)
        Matrix().apply { setRotate(degrees) }.mapRect(rotated)
        val scale = minOf(requestedScale, sqrt(MAX_PIXELS / (rotated.width().toDouble() * rotated.height())))
        val transform = Matrix().apply {
            setTranslate(-rect.left + padding, -rect.top + padding)
            postRotate(degrees)
            postTranslate(-rotated.left, -rotated.top)
            postScale(scale.toFloat(), scale.toFloat())
        }
        val inverse = Matrix().also { check(transform.invert(it)) }
        val output = Bitmap.createBitmap(ceil(rotated.width() * scale).toInt().coerceAtLeast(1),
            ceil(rotated.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (contrast) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val c = 1.18f
            val offset = 128f * (1 - c)
            matrix.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset,0f,c,0f,0f,offset,0f,0f,c,0f,offset,0f,0f,0f,1f,0f)))
            paint.colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(output).apply {
            drawColor(Color.WHITE)
            save()
            concat(transform)
            clipRect(rect)
            drawBitmap(source, 0f, 0f, paint)
            restore()
        }
        output.density = Bitmap.DENSITY_NONE
        return Prepared(output, transform, inverse, scale)
    }

    internal fun mapToSource(line: OcrLine, prepared: Prepared, sourceWidth: Int, sourceHeight: Int): OcrLine? {
        val width = prepared.bitmap.width.toFloat()
        val height = prepared.bitmap.height.toFloat()
        val box = RectF(line.left * width, line.top * height, line.right * width, line.bottom * height)
        val centerX = box.centerX()
        val centerY = box.centerY()
        val baseline = floatArrayOf(centerX, centerY, centerX + 10f, centerY + 10f * line.slope * height / width)
        prepared.inverse.mapPoints(baseline)
        prepared.inverse.mapRect(box)
        val left = (box.left / sourceWidth).coerceIn(0f, 1f)
        val right = (box.right / sourceWidth).coerceIn(0f, 1f)
        val top = (box.top / sourceHeight).coerceIn(0f, 1f)
        val bottom = (box.bottom / sourceHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        val dx = baseline[2] - baseline[0]
        val slope = if (abs(dx) > .001f) (baseline[3] - baseline[1]) / dx * sourceWidth / sourceHeight else 0f
        return line.copy(left = left, right = right, top = top, bottom = bottom, slope = slope)
    }

    internal fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
            else -> null
        }
        return if (matrix == null) source else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun matrixJson(matrix: Matrix): JSONArray = FloatArray(9).let { matrix.getValues(it); JSONArray(it.map { value -> value.toDouble() }) }
    private fun finite(value: Float): Any = if (value.isFinite()) value.toDouble() else JSONObject.NULL
    private fun positionedJson(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left)
        .put("top", line.top).put("right", line.right).put("bottom", line.bottom).put("slope", line.slope)
        .put("confidence", line.confidence?.let(::finite) ?: JSONObject.NULL)
    private fun readingJson(reading: OfflineOcr.Reading) = JSONObject().put("text", reading.text)
        .put("blockers", JSONArray(reading.blockers)).put("warnings", JSONArray(reading.warnings))
        .put("lines", JSONArray(reading.lines.map(::positionedJson)))
    private fun rawLineJson(line: Text.Line) = JSONObject().put("text", line.text)
        .put("confidence", finite(line.confidence)).put("angle", finite(line.angle))
        .put("bounding_box", line.boundingBox?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
        .put("corner_points", line.cornerPoints?.let { JSONArray(it.map { p -> JSONArray(listOf(p.x, p.y)) }) } ?: JSONObject.NULL)
        .put("elements", JSONArray(line.elements.map { element -> JSONObject().put("text", element.text)
            .put("confidence", finite(element.confidence)).put("angle", finite(element.angle))
            .put("bounding_box", element.boundingBox?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL) }))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Variant(val name: String, val contextRows: Int, val tight: Boolean, val deskew: Boolean, val scale: Double, val contrast: Boolean = false)
    companion object {
        private const val PADDING = 24
        private const val MAX_PIXELS = 4_000_000.0
        private val SAMPLE_FILES = listOf("02_desenfoque_suave.jpg", "07_luz_azulada.jpg", "09_impresion_tenue.jpg", "17_foto_girada.jpg")
        private val VARIANTS = listOf(
            Variant("tight_2rows_2x", 2, true, false, 2.0),
            Variant("tight_2rows_3x", 2, true, false, 3.0),
            Variant("tight_3rows_3x", 3, true, false, 3.0),
            Variant("deskew_full_3rows_2x", 3, false, true, 2.0),
            Variant("deskew_tight_3rows_2x", 3, true, true, 2.0),
            Variant("deskew_tight_3rows_3x", 3, true, true, 3.0),
            Variant("deskew_tight_2rows_3x", 2, true, true, 3.0),
            Variant("deskew_tight_3rows_contrast_3x", 3, true, true, 3.0, true),
        )
    }
}

package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic only: records what each bundled engine reads for every primary ML Kit
 * fragment of the bundled test photos, so engine voting can be studied offline.
 * It mirrors the worker's upright/straighten steps; no reference text is used and
 * no document or PDF is created.
 */
@RunWith(AndroidJUnit4::class)
class EngineFusionProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun recordEngineReadingsPerFragment() {
        val run = InstrumentationRegistry.getArguments().getString("probeRun")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "fusion-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "engine-fusion/$run").apply { check(mkdirs()) }
        val selection = InstrumentationRegistry.getArguments().getString("sampleSelection")?.split(',')?.toSet()
        // Optional: read the images of a folder under the app's external files dir instead.
        val folder = InstrumentationRegistry.getArguments().getString("folder")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }?.let { File(context.getExternalFilesDir(null), it) }
        val files = (folder?.list()?.toList() ?: instrumentation.context.assets.list("real-receipt-photos").orEmpty().toList())
            .filter { (it.endsWith(".jpg") || it.endsWith(".png")) && (selection == null || it.take(2) in selection) }.sorted()
        OfflineOcr().use { ml ->
            PaddleOcr(context).use { paddle ->
                IndependentOcr(context).use { tesseract ->
                    for (filename in files) {
                        val bytes = folder?.let { File(it, filename).readBytes() }
                            ?: instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, true)
                        val sample = JSONObject().put("filename", filename)
                        try {
                            var reading = ml.recognize(bitmap)
                            if (reading.suggestedRotation != 0) {
                                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                                    Matrix().apply { setRotate(reading.suggestedRotation.toFloat()) }, true)
                                reading = ml.recognize(bitmap)
                            }
                            if (reading.suggestedRotation == 0 && reading.suggestedDeskew != 0f) {
                                bitmap = OfflineOcr.straighten(bitmap, reading.suggestedDeskew, 10_000_000L).first
                                reading = ml.recognize(bitmap)
                            }
                            val contrast = OfflineOcr.contrastImage(bitmap)
                            val second = try { ml.recognize(contrast) } finally { contrast.recycle() }
                            val fragments = reading.lines
                            val paddleReadings = paddle.recognize(bitmap, fragments)
                            val tessReadings = tesseract.recognize(bitmap)
                            sample.put("width", bitmap.width).put("height", bitmap.height)
                                .put("primary_text", reading.text).put("second_text", second.text)
                                .put("fragments", JSONArray(fragments.mapIndexed { index, line ->
                                    line(line).put("index", index)
                                        .put("paddle", JSONArray(paddleReadings.firstOrNull { it.index == index }?.observations?.map {
                                            JSONObject().put("variant", it.variant.name).put("channel", it.channel.name)
                                                .put("text", it.text).put("confidence", it.confidence.toDouble())
                                                .put("min_char", it.characters.minOfOrNull { c -> c.confidence }?.toDouble() ?: JSONObject.NULL)
                                                .put("raster", it.rasterId)
                                        }.orEmpty()))
                                }))
                                .put("primary_words", JSONArray(reading.words.map(::line)))
                                .put("second_lines", JSONArray(second.lines.map(::line)))
                                .put("second_words", JSONArray(second.words.map(::line)))
                                .put("tesseract", JSONArray(tessReadings.map { words -> JSONArray(words.map(::line)) }))
                                .put("tesseract_assigned", JSONArray(tessReadings.map { words ->
                                    JSONArray(OpticalCellAssignment.assign(words, fragments, bitmap.width, bitmap.height)
                                        .map { assigned -> JSONArray(assigned.sortedBy { it.left }.map(::line)) })
                                }))
                                .put("second_assigned", JSONArray(OpticalCellAssignment.assign(second.words, fragments,
                                    bitmap.width, bitmap.height).map { assigned -> JSONArray(assigned.sortedBy { it.left }.map(::line)) }))
                        } catch (error: Exception) {
                            sample.put("error", error.toString())
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                        File(directory, filename.substringBeforeLast('.') + ".json").writeText(sample.toString(1), Charsets.UTF_8)
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$filename\n") })
                    }
                }
            }
        }
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left.toDouble())
        .put("top", line.top.toDouble()).put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble())
        .put("slope", line.slope.toDouble()).put("confidence", line.confidence?.toDouble() ?: JSONObject.NULL)
}

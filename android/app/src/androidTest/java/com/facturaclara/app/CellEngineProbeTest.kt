package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Raw observations only; no reference text, document import or PDF. */
@RunWith(AndroidJUnit4::class)
class CellEngineProbeTest {
    @Test fun compareIndependentWordsInAllSourceRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("probeRun") ?: "cells-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val selection = (args.getString("sampleSelection") ?: "02,09,17").split(',').toSet()
        val output = File(context.getExternalFilesDir(null), "cell-engine/$label").apply { check(mkdirs()) }
        val data = File(context.cacheDir, "cell-engine-models")
        val models = File(data, "tessdata").apply { mkdirs() }
        for (language in listOf("eng", "spa")) context.assets.open("tessdata/$language.traineddata").use { input ->
            File(models, "$language.traineddata").outputStream().use { input.copyTo(it) }
        }
        val samples = JSONArray()
        val report = JSONObject().put("reference_used", false).put("originals_modified", false).put("samples", samples)
        fun save() = File(output, "result.json").writeText(report.toString(2))
        val tess = TessBaseAPI()
        check(tess.init(data.absolutePath, "eng+spa", TessBaseAPI.OEM_LSTM_ONLY,
            mapOf("load_system_dawg" to "0", "load_freq_dawg" to "0")))
        try {
            OfflineOcr().use { engine ->
                for (filename in instrumentation.context.assets.list("real-receipt-photos").orEmpty()
                    .filter { it.endsWith(".jpg") && it.take(2) in selection }.sorted()) {
                    val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val rotation = bytes.inputStream().use { ExifInterface(it).rotationDegrees }
                    val original = if (rotation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        android.graphics.Matrix().apply { setRotate(rotation.toFloat()) }, true).also { raw.recycle() }
                    original.density = Bitmap.DENSITY_NONE
                    try {
                        val first = engine.recognize(original)
                        val contrast = OfflineOcr.contrastImage(original)
                        val second = try { engine.recognize(contrast) } finally { contrast.recycle() }
                        val anchors = OcrLayout.rows(first.lines)
                        val rows = JSONArray()
                        val sample = JSONObject().put("filename", filename).put("width", original.width).put("height", original.height)
                            .put("whole", JSONArray(listOf(first, second).map { reading(it) })).put("rows", rows)
                        samples.put(sample)
                        for (index in anchors.indices) {
                            val variants = JSONArray()
                            rows.put(JSONObject().put("index", index).put("anchor", lines(anchors[index])).put("variants", variants))
                            for ((span, scale, contrastMode) in listOf(Triple(1, 2.0, false), Triple(3, 2.0, false), Triple(3, 3.0, true))) {
                                val from = (index - span / 2).coerceAtLeast(0)
                                val to = (index + span / 2 + 1).coerceAtMost(anchors.size)
                                val prepared = RegionRaster.prepare(original, anchors.subList(from, to).flatten(), scale, true, contrastMode)
                                try {
                                    val started = SystemClock.elapsedRealtime()
                                    val ml = engine.recognizeRegion(prepared.bitmap)
                                    fun mapped(items: List<OcrLine>) = items.mapNotNull { RegionRaster.map(it, prepared, original.width, original.height) }
                                    val observations = JSONArray()
                                    val variant = JSONObject().put("span", span).put("scale", scale).put("contrast", contrastMode)
                                        .put("ml_lines", lines(mapped(ml.lines))).put("ml_words", lines(mapped(ml.words))).put("tess", observations)
                                    variants.put(variant)
                                    for (threshold in listOf(0, 2)) {
                                        tess.setVariable("thresholding_method", threshold.toString())
                                        tess.pageSegMode = if (span == 1) 7 else 6
                                        tess.setImage(prepared.bitmap)
                                        tess.getHOCRText(0)
                                        val words = mutableListOf<OcrLine>()
                                        val iterator = tess.resultIterator
                                        if (iterator != null) try {
                                            iterator.begin()
                                            do {
                                                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                                                val text = iterator.getUTF8Text(level)
                                                val box = iterator.getBoundingRect(level)
                                                if (!text.isNullOrBlank() && box.width() > 0 && box.height() > 0) {
                                                    words += OcrLine(text, (box.left.toFloat()/prepared.bitmap.width).coerceIn(0f,1f),
                                                        (box.top.toFloat()/prepared.bitmap.height).coerceIn(0f,1f),
                                                        (box.right.toFloat()/prepared.bitmap.width).coerceIn(0f,1f),
                                                        (box.bottom.toFloat()/prepared.bitmap.height).coerceIn(0f,1f),
                                                        confidence=iterator.confidence(level).coerceIn(0f,100f)/100f)
                                                }
                                            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                                        } finally { iterator.delete() }
                                        observations.put(JSONObject().put("threshold", threshold).put("words", lines(mapped(words))))
                                        tess.clear()
                                    }
                                    variant.put("elapsed_ms", SystemClock.elapsedRealtime()-started)
                                } finally { prepared.bitmap.recycle() }
                            }
                            save()
                            if (index % 4 == 0) instrumentation.sendStatus(0, android.os.Bundle().apply {
                                putString("stream", "\n$filename row ${index+1}/${anchors.size}\n") })
                        }
                    } finally { original.recycle() }
                }
            }
        } finally { tess.recycle() }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nCELL_EVIDENCE=${output.absolutePath}\n") })
    }
    private fun reading(value: OfflineOcr.Reading) = JSONObject().put("text", value.text).put("lines", lines(value.lines)).put("words", lines(value.words))
    private fun lines(value: List<OcrLine>) = JSONArray(value.map { line -> JSONObject().put("text", line.text)
        .put("left", line.left).put("top", line.top).put("right", line.right).put("bottom", line.bottom)
        .put("slope", line.slope).put("confidence", line.confidence) })
}

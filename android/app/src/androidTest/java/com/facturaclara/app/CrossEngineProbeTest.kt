package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.ceil
import kotlin.math.floor

/** Diagnostic only: independent pixel observations, no reference text or production export. */
@RunWith(AndroidJUnit4::class)
class CrossEngineProbeTest {
    @Test fun inspectIndependentReadingOfUncertainRows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("probeRun") ?: "cross-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val destination = File(context.getExternalFilesDir(null), "cross-engine/$label")
        check(destination.mkdirs())
        val data = File(context.cacheDir, "cross-engine-models")
        val models = File(data, "tessdata").apply { mkdirs() }
        for (language in listOf("eng", "spa")) {
            instrumentation.context.assets.open("tessdata/$language.traineddata").use { input ->
                File(models, "$language.traineddata").outputStream().use { input.copyTo(it) }
            }
        }
        val tess = TessBaseAPI()
        check(tess.init(data.absolutePath, "eng+spa", TessBaseAPI.OEM_LSTM_ONLY,
            mapOf("load_system_dawg" to "0", "load_freq_dawg" to "0")))
        val samples = JSONArray()
        val report = JSONObject().put("reference_used", false).put("originals_modified", false)
            .put("samples", samples)
        val selection = (args.getString("sampleSelection") ?: "02,07,08,09,17").split(',').toSet()
        try {
            OfflineOcr().use { primary ->
                for (filename in instrumentation.context.assets.list("real-receipt-photos").orEmpty()
                    .filter { it.endsWith(".jpg") && it.take(2) in selection }.sorted()) {
                    val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val orientation = bytes.inputStream().use { ExifInterface(it).rotationDegrees }
                    val original = if (orientation == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        android.graphics.Matrix().apply { setRotate(orientation.toFloat()) }, true).also { raw.recycle() }
                    original.density = Bitmap.DENSITY_NONE
                    try {
                        val first = primary.recognize(original)
                        val contrasted = OfflineOcr.contrastImage(original)
                        val second = try { primary.recognize(contrasted) } finally { contrasted.recycle() }
                        val rows = OcrLayout.rows(first.lines)
                        val bands = JSONArray()
                        val whole = JSONArray()
                        val sample = JSONObject().put("filename", filename).put("rows", bands).put("whole", whole)
                        samples.put(sample)
                        for (mode in listOf(4, 6)) {
                            val prepared = prepare(original, 0, original.height, false)
                            try { whole.put(observe(tess, prepared, mode).put("mode", mode)) }
                            finally { prepared.recycle() }
                        }
                        for (index in rows.indices) {
                            val target = rows[index]
                            val secondRow = second.lines.filter { nearest(it, rows) == index }
                            if (RegionConsensus.reliable(target) && RegionConsensus.reliable(secondRow) &&
                                RegionConsensus.tokens(OcrLayout.text(target)) == RegionConsensus.tokens(OcrLayout.text(secondRow))) continue
                            val variants = JSONArray()
                            val row = JSONObject().put("index", index).put("primary", OcrLayout.text(target))
                                .put("second", OcrLayout.text(secondRow)).put("variants", variants)
                            bands.put(row)
                            for (span in listOf(1, 3)) {
                                val included = rows.subList((index - span / 2).coerceAtLeast(0),
                                    (index + span / 2 + 1).coerceAtMost(rows.size)).flatten()
                                val heights = included.map { (it.bottom - it.top) * original.height }.sorted()
                                val margin = maxOf(8f, heights[heights.size / 2] * .75f)
                                val top = floor(included.minOf { it.top } * original.height - margin).toInt().coerceAtLeast(0)
                                val bottom = ceil(included.maxOf { it.bottom } * original.height + margin).toInt().coerceAtMost(original.height)
                                for (contrast in listOf(false, true)) {
                                    val prepared = prepare(original, top, bottom, contrast)
                                    try {
                                        variants.put(observe(tess, prepared, if (span == 1) 7 else 6)
                                            .put("context", span).put("contrast", contrast)
                                            .put("top", top).put("bottom", bottom)
                                            .put("width", prepared.width).put("height", prepared.height))
                                    } finally { prepared.recycle() }
                                }
                            }
                        }
                        File(destination, "result.json").writeText(report.toString(2))
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nCross engine $filename: ${bands.length()} uncertain rows\n") })
                    } finally { original.recycle() }
                }
            }
        } finally { tess.recycle() }
    }

    private fun nearest(line: OcrLine, rows: List<List<OcrLine>>) =
        rows.indices.minByOrNull { OcrLayout.rowDistance(line, rows[it]) } ?: -1

    private fun prepare(source: Bitmap, top: Int, bottom: Int, contrast: Boolean): Bitmap {
        val padded = Bitmap.createBitmap(source.width + 48, bottom - top + 48, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply { drawColor(Color.WHITE); clipRect(24, 24, source.width + 24, bottom - top + 24)
            drawBitmap(source, 24f, 24f - top, null) }
        val processed = if (contrast) OfflineOcr.contrastImage(padded).also { padded.recycle() } else padded
        val enlarged = Bitmap.createScaledBitmap(processed, processed.width * 2, processed.height * 2, true)
        if (enlarged !== processed) processed.recycle()
        enlarged.density = Bitmap.DENSITY_NONE
        return enlarged
    }

    private fun observe(tess: TessBaseAPI, bitmap: Bitmap, mode: Int): JSONObject {
        val start = SystemClock.elapsedRealtime()
        tess.pageSegMode = mode
        tess.setImage(bitmap)
        tess.getHOCRText(0)
        val words = JSONArray()
        val iterator = tess.resultIterator
        if (iterator != null) try {
            iterator.begin()
            do {
                val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!word.isNullOrBlank()) words.put(JSONObject().put("text", word)
                    .put("confidence", iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                    .put("box", JSONArray(iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD).toList())))
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally { iterator.delete() }
        val result = JSONObject().put("text", tess.utF8Text).put("words", words)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - start)
        tess.clear()
        return result
    }
}

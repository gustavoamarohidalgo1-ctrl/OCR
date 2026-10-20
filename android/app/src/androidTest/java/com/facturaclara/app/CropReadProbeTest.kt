package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

/**
 * Diagnostic only: reads explicit crops of one bundled test photo with ML Kit, optionally
 * turned by quarter turns and enlarged, to test whether a region is readable at all.
 * Arguments: photo (asset file name), crops ("name:x0,y0,x1,y1,turns,scale;..." with
 * normalized coordinates of the original photo), probeRun.
 */
@RunWith(AndroidJUnit4::class)
class CropReadProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun readExplicitCrops() {
        val arguments = InstrumentationRegistry.getArguments()
        val photo = arguments.getString("photo")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }
        val spec = arguments.getString("crops")
        assumeTrue("Pass -e photo and -e crops", photo != null && spec != null)
        val run = arguments.getString("probeRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "crops-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "crop-read/$run").apply { mkdirs() }
        val bytes = instrumentation.context.assets.open("real-receipt-photos/$photo").use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
        val results = JSONArray()
        OfflineOcr().use { ml ->
            for (item in spec!!.split(';').filter { it.isNotBlank() }) {
                val (name, values) = item.split(':', limit = 2)
                val v = values.split(',').map { it.trim().toFloat() }
                val left = (v[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val top = (v[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val right = (v[2] * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                val bottom = (v[3] * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                val turns = v.getOrElse(4) { 0f }.toInt()
                val scale = v.getOrElse(5) { 1f }
                val region = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top,
                    Matrix().apply { postRotate(90f * turns) }, true)
                val scaled = Bitmap.createScaledBitmap(region, max(32, (region.width * scale).toInt()),
                    max(32, (region.height * scale).toInt()), true)
                val reading = ml.recognizeRegion(scaled)
                results.put(JSONObject().put("name", name).put("text", reading.text)
                    .put("lines", JSONArray(reading.lines.map {
                        JSONObject().put("text", it.text).put("confidence", it.confidence?.toDouble() ?: JSONObject.NULL)
                    })))
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$name: ${reading.text.replace('\n', '|')}\n") })
                if (scaled !== region) scaled.recycle()
                region.recycle()
            }
        }
        File(directory, photo!!.substringBeforeLast('.') + ".json").writeText(results.toString(1), Charsets.UTF_8)
    }
}

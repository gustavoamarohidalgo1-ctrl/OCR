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
import kotlin.math.max
import kotlin.math.min

/**
 * Diagnostic only: re-reads every row of the page reading at 3x (row band crops) and every
 * strip of sideways text turned both ways, with ML Kit, and records the readings mapped
 * back to page coordinates, so acceptance rules can be simulated offline against the
 * references. Mirrors the worker's upright/straighten steps. Nothing is written to documents.
 * Arguments: sampleSelection (two-digit prefixes), probeRun.
 */
@RunWith(AndroidJUnit4::class)
class RereadProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun rereadRowsAndSidewaysStrips() {
        val arguments = InstrumentationRegistry.getArguments()
        val run = arguments.getString("probeRun")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "reread-${System.currentTimeMillis()}"
        val directory = File(context.getExternalFilesDir(null), "reread/$run").apply { mkdirs() }
        val selection = arguments.getString("sampleSelection")?.split(',')?.toSet()
        val files = instrumentation.context.assets.list("real-receipt-photos").orEmpty()
            .filter { it.endsWith(".jpg") && (selection == null || it.take(2) in selection) }.sorted()
        OfflineOcr().use { ml ->
            for (filename in files) {
                val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
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
                    val lines = reading.lines
                    val heights = lines.map { it.bottom - it.top }.sorted()
                    val height = heights.getOrElse(heights.size / 2) { .02f }
                    val rows = JSONArray()
                    OcrLayout.rows(lines).forEachIndexed { index, row ->
                        val top = row.minOf { it.top } - .6f * height
                        val bottom = row.maxOf { it.bottom } + .6f * height
                        val left = row.minOf { it.left } - 1.5f * height
                        val right = row.maxOf { it.right } + 1.5f * height
                        val box = Box(left, top, right, bottom)
                        rows.put(JSONObject().put("index", index).put("box", box.json())
                            .put("original", JSONArray(row.map(::line)))
                            .put("reread", JSONArray(read(ml, bitmap, box, 0, 3f).map(::line)))
                            .put("reread2", JSONArray(read(ml, bitmap, box, 0, 2f).map(::line))))
                    }
                    val strips = JSONArray()
                    sideways(lines, bitmap.width, bitmap.height).forEach { group ->
                        val pad = group.minOf { it.right - it.left } * .4f
                        val box = Box(group.minOf { it.left } - pad, group.minOf { it.top } - pad, group.maxOf { it.right } + pad, group.maxOf { it.bottom } + pad)
                        strips.put(JSONObject().put("box", box.json()).put("original", JSONArray(group.map(::line)))
                            .put("clockwise", JSONArray(read(ml, bitmap, box, 1, 2f).map(::line)))
                            .put("counterclockwise", JSONArray(read(ml, bitmap, box, 3, 2f).map(::line))))
                    }
                    sample.put("width", bitmap.width).put("height", bitmap.height)
                        .put("primary", JSONArray(lines.map(::line))).put("rows", rows).put("strips", strips)
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

    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun json() = JSONObject().put("left", left.toDouble()).put("top", top.toDouble()).put("right", right.toDouble()).put("bottom", bottom.toDouble())
    }

    /** Fragments printed sideways (much taller than wide in pixels), grouped by column overlap. */
    private fun sideways(lines: List<OcrLine>, width: Int, height: Int): List<List<OcrLine>> {
        val tall = lines.filter { (it.bottom - it.top) * height >= 2.5f * (it.right - it.left) * width && it.text.trim().length >= 3 }
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in tall.sortedBy { it.left }) {
            val group = groups.firstOrNull { g -> g.any { min(it.right, line.right) - max(it.left, line.left) > 0f } }
            if (group != null) group += line else groups += mutableListOf(line)
        }
        return groups
    }

    /** Reads [box] (page-normalized) turned by quarter turns and enlarged; lines mapped back to the page. */
    private fun read(ml: OfflineOcr, page: Bitmap, box: Box, turns: Int, scale: Float): List<OcrLine> {
        val left = (box.left.coerceIn(0f, 1f) * page.width).toInt()
        val top = (box.top.coerceIn(0f, 1f) * page.height).toInt()
        val right = (box.right.coerceIn(0f, 1f) * page.width).toInt().coerceAtLeast(left + 1)
        val bottom = (box.bottom.coerceIn(0f, 1f) * page.height).toInt().coerceAtLeast(top + 1)
        val region = Bitmap.createBitmap(page, left, top, right - left, bottom - top, Matrix().apply { postRotate(90f * turns) }, true)
        val scaled = Bitmap.createScaledBitmap(region, max(32, (region.width * scale).toInt()), max(32, (region.height * scale).toInt()), true)
        return try {
            val l = left.toFloat() / page.width
            val t = top.toFloat() / page.height
            val w = (right - left).toFloat() / page.width
            val h = (bottom - top).toFloat() / page.height
            ml.recognizeRegion(scaled).lines.mapNotNull { line ->
                // Corners in the turned crop -> the crop before turning -> the page.
                val corners = listOf(line.left to line.top, line.right to line.top, line.left to line.bottom, line.right to line.bottom).map { (u, v) ->
                    when (turns % 4) {
                        1 -> v to 1f - u
                        3 -> 1f - v to u
                        else -> u to v
                    }
                }.map { (x, y) -> (l + x * w) to (t + y * h) }
                val x0 = corners.minOf { it.first }.coerceIn(0f, 1f); val x1 = corners.maxOf { it.first }.coerceIn(0f, 1f)
                val y0 = corners.minOf { it.second }.coerceIn(0f, 1f); val y1 = corners.maxOf { it.second }.coerceIn(0f, 1f)
                if (x1 > x0 && y1 > y0) OcrLine(line.text, x0, y0, x1, y1, 0f, line.confidence) else null
            }
        } finally {
            if (scaled !== region) scaled.recycle()
            region.recycle()
        }
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left.toDouble())
        .put("top", line.top.toDouble()).put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble())
        .put("confidence", line.confidence?.toDouble() ?: JSONObject.NULL)
}

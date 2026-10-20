package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Runs the new complete-page candidate in isolation; never declares a production PDF. */
@RunWith(AndroidJUnit4::class)
class IndependentPageOcrInstrumentedTest {
    @Test fun probeOriginalReceipts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("runLabel") ?: "page-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val selection = args.getString("sampleSelection")?.split(',')?.toSet()
        val profile = PaddleTextDetector.Profile.valueOf(args.getString("detectorProfile") ?: "V6_SMALL")
        val root = File(context.getExternalFilesDir(null), "independent-page/$label").apply { check(mkdirs()) }
        val samples = JSONArray()
        val report = JSONObject().put("reference_used", false).put("production_pdf_generated", false)
            .put("images_edited", false).put("samples", samples)
        fun save() = File(root, "summary.json").writeText(report.toString(2))
        val started = SystemClock.elapsedRealtime()
        try {
            OfflineOcr().use { ml ->
                IndependentOcr(context).use { tess ->
                    PaddleOcr(context).use { rec ->
                        PaddleTextDetector(context, profile).use { det ->
                            val files = instrumentation.context.assets.list("real-receipt-photos").orEmpty()
                                .filter { it.endsWith(".jpg") && (selection == null || it.take(2) in selection) }.sorted()
                            check(files.isNotEmpty())
                            for (filename in files) {
                                val bytes = instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }
                                val sampleDir = File(root, filename.removeSuffix(".jpg")).apply { check(mkdirs()) }
                                val image = upright(bytes)
                                val before = pixels(image)
                                val sampleStart = SystemClock.elapsedRealtime()
                                try {
                                    val first = ml.recognize(image)
                                    val contrast = OfflineOcr.contrastImage(image)
                                    val second = try { ml.recognize(contrast) } finally { contrast.recycle() }
                                    val reading = IndependentPageVerifier(ml, rec, det, tess)
                                        .verify(image, listOf(first, second), File(sampleDir, "verification.json"))
                                    val text = if (reading.unresolved == 0) OcrLayout.text(reading.lines) else first.text
                                    val checks = InvoiceChecks.inspect(text)
                                    val layout = InvoiceGeometryChecks.inspect(text, reading.lines)
                                    val capture = first.captureBlockers + second.captureBlockers
                                    val blockers = checks.blockers + layout + capture +
                                        if (first.suggestedRotation != 0 || second.suggestedRotation != 0) listOf("unresolved_orientation") else emptyList()
                                    val candidate = reading.unresolved == 0 && blockers.isEmpty()
                                    val sample = JSONObject().put("filename", filename).put("source_sha256", sha(bytes))
                                        .put("elapsed_ms", SystemClock.elapsedRealtime() - sampleStart)
                                        .put("unresolved", reading.unresolved).put("candidate_complete", candidate)
                                        .put("text", text).put("blockers", JSONArray(blockers))
                                        .put("lines", JSONArray(reading.lines.map(::line)))
                                        .put("primary_text", first.text)
                                        .put("primary_words", JSONArray(first.words.map(::line)))
                                    assertFalse(image.isRecycled)
                                    assertEquals(before, pixels(image))
                                    assertEquals(sha(bytes), sha(instrumentation.context.assets.open("real-receipt-photos/$filename").use { it.readBytes() }))
                                    sample.put("original_unchanged", true)
                                    File(sampleDir, "result.json").writeText(sample.toString(2))
                                    samples.put(sample)
                                    save()
                                    instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "\nIndependent page $filename candidate=$candidate unresolved=${reading.unresolved} time=${SystemClock.elapsedRealtime() - sampleStart}ms\n")
                                    })
                                } finally { image.recycle() }
                            }
                        }
                    }
                }
            }
            report.put("completed", true).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            save()
        } finally {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nINDEPENDENT_PAGE_EVIDENCE=${root.absolutePath}\n") })
        }
    }

    private fun upright(bytes: ByteArray): Bitmap {
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return image
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also { if (it !== image) image.recycle() }
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left.toDouble())
        .put("top", line.top.toDouble()).put("right", line.right.toDouble()).put("bottom", line.bottom.toDouble())
        .put("slope", line.slope.toDouble()).put("confidence", line.confidence?.toDouble())

    private fun pixels(image: Bitmap): String {
        val buffer = java.nio.ByteBuffer.allocate(image.byteCount)
        image.copyPixelsToBuffer(buffer)
        return sha(buffer.array())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Raw observations only. No reference file, correction table, import or PDF. */
@RunWith(AndroidJUnit4::class)
class PaddleOcrInstrumentedTest {
    @Test fun probeOriginalReceipts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val label = args.getString("probeRun") ?: "paddle-${System.currentTimeMillis()}"
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        val selection = (args.getString("sampleSelection") ?: "02,09,17").split(',').toSet()
        val kinds = (args.getString("probeKinds") ?: "fragments,rows").split(',').toSet()
        require(kinds.isNotEmpty() && kinds.all { it in setOf("fragments", "words", "rows") })
        val directory = File(context.getExternalFilesDir(null), "paddle-ocr/$label").apply {
            check(mkdirs()) { "Probe destination already exists" }
        }
        val samples = JSONArray()
        val report = JSONObject().put("reference_used", false).put("originals_modified", false)
            .put("model", "latin_PP-OCRv5_mobile_rec").put("model_sha256", PaddleOcr.MODEL_SHA)
            .put("dictionary_sha256", PaddleOcr.DICTIONARY_SHA).put("runtime", PaddleOcr.RUNTIME_VERSION)
            .put("geometry_source", "fresh offline ML Kit on EXIF-upright original; text discarded before Paddle input")
            .put("channels", JSONArray(listOf("BGR", "RGB"))).put("samples", samples)
        fun save() = File(directory, "result.json").writeText(report.toString(2))
        val started = SystemClock.elapsedRealtime()
        try {
            PaddleOcr(context).use { paddle ->
                OfflineOcr().use { detector ->
                    val files = instrumentation.context.assets.list("real-receipt-photos").orEmpty()
                        .filter { it.endsWith(".jpg") && it.take(2) in selection }.sorted()
                    check(files.isNotEmpty()) { "No matching original photos" }
                    for (filename in files) {
                        val source = "real-receipt-photos/$filename"
                        val bytes = instrumentation.context.assets.open(source).use { it.readBytes() }
                        val original = upright(bytes)
                        val sourceSha = sha(bytes)
                        try {
                            val pixelsBefore = pixelSha(original)
                            val primary = detector.recognize(original)
                            // The detector's text is recorded for subsequent analysis but is
                            // deliberately erased in all boxes sent to the independent engine.
                            val boxes = primary.lines.map { it.copy(text = "", confidence = null) }
                            val words = primary.words.map { it.copy(text = "", confidence = null) }
                            val rows = OcrLayout.rows(boxes)
                            val observations = JSONArray()
                            val sample = JSONObject().put("filename", filename).put("source_sha256_before", sourceSha)
                                .put("width", original.width).put("height", original.height)
                                .put("primary_lines", JSONArray(primary.lines.map(::line)))
                                .put("primary_words", JSONArray(primary.words.map(::line)))
                                .put("observations", observations)
                            samples.put(sample)
                            save()
                            val channels = listOf(PaddleOcr.Channel.BGR, PaddleOcr.Channel.RGB)
                            for (kind in listOf("fragments", "words", "rows").filter { it in kinds }) {
                                val groups = when (kind) {
                                    "rows" -> rows
                                    "words" -> words.map { listOf(it) }
                                    else -> boxes.map { listOf(it) }
                                }
                                for ((index, group) in groups.withIndex()) {
                                    val result = if (kind == "rows") paddle.recognizeRows(original, listOf(group), channels).single()
                                        else paddle.recognize(original, group, channels).single()
                                    observations.put(JSONObject().put("kind", kind).put("index", index)
                                        .put("source_geometry", JSONArray(group.map(::line)))
                                        .put("variants", JSONArray(result.observations.map(::observation))))
                                    save()
                                    if (index % 5 == 0) instrumentation.sendStatus(0, Bundle().apply {
                                        putString("stream", "\nPaddle $filename $kind ${index + 1}/${groups.size}\n")
                                    })
                                }
                            }
                            assertFalse("Reader must not recycle the caller's bitmap", original.isRecycled)
                            assertEquals("Reader must not change source pixels", pixelsBefore, pixelSha(original))
                            val after = instrumentation.context.assets.open(source).use { sha(it.readBytes()) }
                            assertEquals("Original source file changed", sourceSha, after)
                            sample.put("source_sha256_after", after).put("pixels_unchanged", true)
                            save()
                        } finally { original.recycle() }
                    }
                }
            }
            report.put("completed", true).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            save()
        } catch (error: Throwable) {
            report.put("completed", false).put("failure_type", error.javaClass.name)
                .put("failure_message", error.message).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            save()
            throw error
        } finally {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPADDLE_EVIDENCE=${directory.absolutePath}\n") })
        }
    }

    @Test fun cancellationRejectsNewWorkWithoutRecyclingInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        try {
            PaddleOcr(context).use { reader ->
                reader.cancel()
                try {
                    reader.recognize(bitmap, listOf(OcrLine("", 0f, 0f, 1f, 1f)))
                    fail("A cancelled reader must reject recognition")
                } catch (_: CancellationException) {
                    assertFalse(bitmap.isRecycled)
                }
            }
        } finally { bitmap.recycle() }
    }

    @Test fun onePixelWideRegionPadsRightWithoutStretchingOrChangingOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Proportional 1x96 -> 1x48 resize averages each black/white pair to128.
        // Its final input must retain that one column and append15 white columns.
        val bitmap = Bitmap.createBitmap(1, 96, Bitmap.Config.ARGB_8888)
        for (y in 0 until bitmap.height) bitmap.setPixel(0, y,
            if (y % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        val before = pixelSha(bitmap)
        val expected = MessageDigest.getInstance("SHA-256").apply {
            update(ByteBuffer.allocate(8).putInt(16).putInt(48).array())
            val row = ByteArray(16 * 3) { 255.toByte() }
            row[0] = 128.toByte(); row[1] = 128.toByte(); row[2] = 128.toByte()
            repeat(48) { update(row) }
        }
        val expectedRaster = hex(expected.digest())
        try {
            PaddleOcr(context).use { reader ->
                val result = reader.recognize(bitmap, listOf(OcrLine("", 0f, 0f, 1f, 1f))).single()
                assertEquals(setOf(PaddleOcr.Variant.TIGHT, PaddleOcr.Variant.MARGIN,
                    PaddleOcr.Variant.DESKEW_MARGIN, PaddleOcr.Variant.WIDE_HORIZONTAL_DESKEW,
                    PaddleOcr.Variant.WIDE_HORIZONTAL), result.observations.map { it.variant }.toSet())
                assertEquals(5, result.observations.size)
                result.observations.forEach { observation ->
                    assertEquals(16, observation.tensorWidth)
                    assertEquals("Padding must preserve the glyph column and affect raster identity",
                        expectedRaster, observation.rasterId)
                    assertTrue(observation.confidence.isFinite() && observation.confidence in 0f..1f)
                    assertTrue(observation.characters.size <= 1000)
                }
                assertFalse(bitmap.isRecycled)
                assertEquals(before, pixelSha(bitmap))
            }
            assertFalse("Closing must preserve caller ownership", bitmap.isRecycled)
            assertEquals(before, pixelSha(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun nativeCancellationStopsOwnerWithinFiveSecondsAndPreservesInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(3200, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val before = pixelSha(bitmap)
        val reader = PaddleOcr(context)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val runField = PaddleOcr::class.java.getDeclaredField("activeRun").apply { isAccessible = true }
        val lockField = PaddleOcr::class.java.getDeclaredField("lifecycleLock").apply { isAccessible = true }
        val lifecycle = lockField.get(reader)
        val owner = Thread({
            try {
                started.countDown()
                reader.recognize(bitmap, List(128) { OcrLine("", 0f, 0f, 1f, 1f) })
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                // The owner, not the cancellation thread, releases native resources.
                try { reader.close() } catch (error: Throwable) { failure.compareAndSet(null, error) }
            }
        }, "paddle-native-cancellation-test").apply { isDaemon = true }
        owner.start()
        try {
            assertTrue("Owner did not start", started.await(2, TimeUnit.SECONDS))
            val deadline = SystemClock.elapsedRealtime() + 5000
            var active = false
            while (owner.isAlive && SystemClock.elapsedRealtime() < deadline) {
                active = synchronized(lifecycle) { runField.get(reader) != null }
                if (active) break
                Thread.sleep(1)
            }
            assertTrue("No native RunOptions became active before deadline: ${failure.get()}", active)
            val cancelledAt = SystemClock.elapsedRealtime()
            reader.cancel()
            owner.join(5000)
            assertFalse("Native OCR owner remained active after cancellation", owner.isAlive)
            assertTrue("Expected CancellationException, got ${failure.get()}", failure.get() is CancellationException)
            assertFalse("Cancelling must not recycle caller input", bitmap.isRecycled)
            assertEquals("Cancelling must not change input pixels", before, pixelSha(bitmap))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nPADDLE_NATIVE_CANCEL_MS=${SystemClock.elapsedRealtime() - cancelledAt}\n")
            })
        } finally {
            reader.cancel()
            owner.join(5000)
            // An unexpectedly stuck native call still owns input; never recycle
            // that bitmap to make a failing test appear to clean up successfully.
            if (!owner.isAlive) bitmap.recycle()
        }
    }

    private fun upright(bytes: ByteArray): Bitmap {
        val raw = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val orientation = bytes.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val original = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
            .also { if (it !== raw) raw.recycle() }
        original.density = Bitmap.DENSITY_NONE
        return original
    }

    private fun line(line: OcrLine) = JSONObject().put("text", line.text).put("left", line.left)
        .put("top", line.top).put("right", line.right).put("bottom", line.bottom)
        .put("slope", line.slope).put("confidence", line.confidence)

    private fun observation(value: PaddleOcr.Observation) = JSONObject().put("variant", value.variant.name)
        .put("channel", value.channel.name).put("text", value.text).put("confidence", value.confidence)
        .put("line", line(value.line)).put("tensor_width", value.tensorWidth).put("elapsed_ms", value.elapsedMs)
        .put("raster_id", value.rasterId).put("characters", JSONArray(value.characters.map { char ->
            JSONObject().put("text", char.text).put("confidence", char.confidence)
                .put("timestep", char.timestep).put("class_index", char.classIndex)
        }))

    private fun pixelSha(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width)
        val row = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            pixels.forEachIndexed { index, color ->
                for (channel in 0..3) row[index * 4 + channel] = (color ushr (channel * 8)).toByte()
            }
            digest.update(row)
        }
        return hex(digest.digest())
    }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PaddleTextDetectorInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun blankPageHasNoDetectedTextAndKeepsOriginalPixels() {
        val source = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = hash(source)
        try {
            PaddleTextDetector(context).use { detector ->
                val result = detector.detect(source)
                assertEquals(1024, result.inputWidth)
                assertEquals(768, result.inputHeight)
                assertTrue("A blank white page must not acquire text boxes", result.detections.isEmpty())
                assertTrue(result.contourCount <= PaddleTextDetector.MAX_CANDIDATES)
                assertEquals(before, hash(source))
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun v6BlankPageUsesItsPinnedModelAndOfficialResizeDefaults() {
        val source = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = hash(source)
        try {
            PaddleTextDetector(context, PaddleTextDetector.Profile.V6_SMALL).use { detector ->
                val result = detector.detect(source)
                assertEquals(PaddleTextDetector.Profile.V6_SMALL, result.profile)
                assertEquals(detector.profile.modelSha, result.modelSha)
                assertEquals(992, result.inputWidth); assertEquals(736, result.inputHeight)
                assertTrue("A blank white page must not acquire text boxes", result.detections.isEmpty())
                assertEquals(.2f, result.settings["threshold"])
                assertEquals(.45f, result.settings["box_threshold"])
                assertEquals(3000, result.settings["max_candidates"])
                assertEquals(before, hash(source))
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun v6RasterOverBudgetIsReducedByAreaInsteadOfRejectingThePage() {
        val source = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = hash(source)
        try {
            PaddleTextDetector(context, PaddleTextDetector.Profile.V6_SMALL).use { detector ->
                // 2048x2048 is 4.19MP; one area scale and a floor to the stride fit the 4MP budget.
                val result = detector.detect(source)
                assertEquals(1984, result.inputWidth); assertEquals(1984, result.inputHeight)
                assertTrue(result.inputWidth.toLong() * result.inputHeight <= detector.profile.maxRasterPixels)
                assertTrue("A blank white page must not acquire text boxes", result.detections.isEmpty())
                assertEquals(before, hash(source))
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun realPhoto02ReportsProbabilityRangeWithoutChangingSource() = probeOriginal("02", PaddleTextDetector.Profile.V5_MOBILE)

    @Test fun v6DetectsRealPhoto15IndependentlyWithoutChangingSource() = probeOriginal("15", PaddleTextDetector.Profile.V6_SMALL)

    private fun probeOriginal(prefix: String, profile: PaddleTextDetector.Profile) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testAssets = instrumentation.context.assets
        val filename = testAssets.list("real-receipt-photos").orEmpty().single { it.startsWith("${prefix}_") && it.endsWith(".jpg") }
        val bytes = testAssets.open("real-receipt-photos/$filename").use { it.readBytes() }
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val before = hash(source)
        try {
            PaddleTextDetector(context, profile).use { detector ->
                val result = detector.detect(source)
                assertTrue("Photo returned no independent detections", result.detections.isNotEmpty())
                assertTrue(result.detections.all { it.line.text.isEmpty() && it.confidence in profile.boxThreshold..1f })
                assertTrue(result.detections.all { it.points.size == 4 && it.points.all { point -> point.x in 0f..1f && point.y in 0f..1f } })
                assertEquals(profile.modelSha, result.modelSha)
                assertEquals(before, hash(source))
                val report = JSONObject().put("filename", filename).put("profile", profile.name)
                    .put("raw_probability_minimum", result.probabilityMinimum).put("raw_probability_maximum", result.probabilityMaximum)
                    .put("probabilities_clamped", result.probabilityClampedCount).put("detections", result.detections.size)
                    .put("raster_width", result.inputWidth).put("raster_height", result.inputHeight).put("elapsed_ms", result.elapsedMs)
                    .put("source_pixels_unchanged", true)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDETECTOR_NATIVE_MAP=$report\n") })
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun marginSamplesAdditionalSourcePixelsAndInverseMapPreservesCoordinates() {
        val source = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).apply {
            drawRect(24f, 16f, 136f, 64f, Paint().apply { color = Color.RED })
            drawRect(32f, 24f, 128f, 56f, Paint().apply { color = Color.BLACK })
        }
        val before = hash(source)
        val detection = detection(listOf(PointF(.2f, .3f), PointF(.8f, .3f), PointF(.8f, .7f), PointF(.2f, .7f)))
        try {
            PaddleTextDetector(context).use { detector ->
                val tight = detector.rectify(source, detection)
                val expanded = detector.rectify(source, detection, .25f)
                try {
                    assertEquals(96, tight.width); assertEquals(32, tight.height)
                    assertEquals(112, expanded.width); assertEquals(48, expanded.height)
                    assertEquals(Color.BLACK, tight.getPixel(tight.width / 2, tight.height / 2))
                    assertEquals("Margin must read source pixels, not invent a white border", Color.RED, expanded.getPixel(2, 2))
                    val mapped = detector.mapFromRectified(source, detection, OcrLine("unchanged", 0f, 0f, 1f, 1f, confidence = .9f))!!
                    assertEquals("unchanged", mapped.text)
                    assertEquals(.2f, mapped.left, 1e-5f); assertEquals(.3f, mapped.top, 1e-5f)
                    assertEquals(.8f, mapped.right, 1e-5f); assertEquals(.7f, mapped.bottom, 1e-5f)
                    val expandedMap = detector.mapFromRectified(source, detection, OcrLine("", 0f, 0f, 1f, 1f), .25f)!!
                    assertEquals(.15f, expandedMap.left, 1e-5f); assertEquals(.2f, expandedMap.top, 1e-5f)
                    assertEquals(.85f, expandedMap.right, 1e-5f); assertEquals(.8f, expandedMap.bottom, 1e-5f)
                } finally { tight.recycle(); expanded.recycle() }
            }
            assertFalse(source.isRecycled)
            assertEquals(before, hash(source))
        } finally { source.recycle() }
    }

    @Test fun perspectiveAndVerticalRotationInverseMapAllFourCorners() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val perspective = detection(listOf(PointF(.1f, .1f), PointF(.9f, .1f), PointF(.7f, .9f), PointF(.3f, .9f)))
        val vertical = detection(listOf(PointF(.3f, .1f), PointF(.5f, .1f), PointF(.5f, .9f), PointF(.3f, .9f)))
        try {
            PaddleTextDetector(context).use { detector ->
                val entire = detector.mapFromRectified(source, perspective, OcrLine("", 0f, 0f, 1f, 1f))!!
                assertEquals(.1f, entire.left, 1e-5f); assertEquals(.1f, entire.top, 1e-5f)
                assertEquals(.9f, entire.right, 1e-5f); assertEquals(.9f, entire.bottom, 1e-5f)
                // This trapezoid has x=(.8u+.5v+.1)/(1+v),
                // y=(1.7v+.1)/(1+v). An affine bounding-box map is incorrect.
                val interior = detector.mapFromRectified(source, perspective, OcrLine("", .25f, .25f, .75f, .75f))!!
                assertEquals(.34f, interior.left, 1e-5f); assertEquals(.42f, interior.top, 1e-5f)
                assertEquals(.66f, interior.right, 1e-5f); assertEquals(.7857143f, interior.bottom, 1e-5f)
                val crop = detector.rectify(source, vertical)
                try { assertEquals(160, crop.width); assertEquals(40, crop.height) } finally { crop.recycle() }
                // Left half of the CCW-rotated crop is the top half in the page.
                val half = detector.mapFromRectified(source, vertical, OcrLine("", 0f, 0f, .5f, 1f))!!
                assertEquals(.3f, half.left, 1e-5f); assertEquals(.1f, half.top, 1e-5f)
                assertEquals(.5f, half.right, 1e-5f); assertEquals(.5f, half.bottom, 1e-5f)
            }
        } finally { source.recycle() }
    }

    @Test fun supplementaryUprightNarrowDigitKeepsOrientationWhileVerticalDetectorQuadRotates() {
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).drawRect(98f, 30f, 102f, 170f, Paint().apply { color = Color.BLACK })
        val quad = detection(listOf(PointF(.45f, .1f), PointF(.55f, .1f), PointF(.55f, .9f), PointF(.45f, .9f)))
        val uprightWord = quad.copy(rotateVertical = false)
        val before = hash(source)
        try {
            PaddleTextDetector(context).use { detector ->
                val upright = detector.rectify(source, uprightWord)
                val vertical = detector.rectify(source, quad)
                try {
                    assertEquals(20, upright.width); assertEquals(160, upright.height)
                    assertEquals(160, vertical.width); assertEquals(20, vertical.height)
                    assertEquals(Color.BLACK, upright.getPixel(10, 80))
                    assertEquals(Color.WHITE, upright.getPixel(1, 80))
                    assertEquals(Color.BLACK, vertical.getPixel(80, 10))
                    assertEquals(Color.WHITE, vertical.getPixel(80, 1))
                    val uprightHalf = detector.mapFromRectified(source, uprightWord, OcrLine("1", 0f, 0f, 1f, .5f))!!
                    val rotatedHalf = detector.mapFromRectified(source, quad, OcrLine("1", 0f, 0f, .5f, 1f))!!
                    for (mapped in listOf(uprightHalf, rotatedHalf)) {
                        assertEquals(.45f, mapped.left, 1e-5f); assertEquals(.1f, mapped.top, 1e-5f)
                        assertEquals(.55f, mapped.right, 1e-5f); assertEquals(.5f, mapped.bottom, 1e-5f)
                    }
                } finally { upright.recycle(); vertical.recycle() }
            }
            assertFalse(source.isRecycled)
            assertEquals(before, hash(source))
        } finally { source.recycle() }
    }

    @Test fun sourceCoverageRejectsPartialWordAndUsesExactPlannedMargin() {
        val source = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        val region = detection(listOf(PointF(.30f, .20f), PointF(.38f, .20f), PointF(.38f, .24f), PointF(.30f, .24f)))
        try {
            PaddleTextDetector(context).use { detector ->
                val full = OcrLine("12345", .31f, .205f, .37f, .235f)
                assertTrue(detector.coversSourceWord(source, region, full))
                assertTrue(detector.coversSourceWord(source, region, full.copy(text = "unrelated")))
                val partial = OcrLine("12345", .30f, .20f, .45f, .24f)
                assertFalse("Positive overlap cannot prove the final digits were seen", detector.coversSourceWord(source, region, partial))
                assertFalse(detector.coversSourceWord(source, region, partial, .08f))
                val withinMargin = OcrLine("1", .298f, .198f, .382f, .242f)
                assertFalse(detector.coversSourceWord(source, region, withinMargin))
                assertTrue(detector.coversSourceWord(source, region, withinMargin, .08f))
                assertTrue(detector.coversSourceWord(source, region, OcrLine("1", .31f, .21f, .3809f, .23f)))
                assertFalse("Rounding allowance must never exceed one source pixel",
                    detector.coversSourceWord(source, region, OcrLine("1", .31f, .21f, .3811f, .23f)))
            }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun sourceCoverageChecksEveryQuadEdgeInsteadOfAabbOrWordCenter() {
        val source = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val rotated = detection(listOf(PointF(.5f, .1f), PointF(.9f, .5f), PointF(.5f, .9f), PointF(.1f, .5f)))
        try {
            PaddleTextDetector(context).use { detector ->
                assertTrue(detector.coversSourceWord(source, rotated, OcrLine("inside", .45f, .45f, .55f, .55f)))
                assertFalse("The detector AABB includes pixels outside its rotated quad",
                    detector.coversSourceWord(source, rotated, OcrLine("outside", .15f, .15f, .25f, .25f)))
                assertFalse("A word center inside the quad does not prove complete coverage",
                    detector.coversSourceWord(source, rotated, OcrLine("partial", .15f, .15f, .85f, .85f)))
                assertFalse(detector.coversSourceWord(source, rotated, OcrLine("partial", .15f, .15f, .85f, .85f), .08f))
            }
        } finally { source.recycle() }
    }

    @Test fun invalidGeometryAndOversizedInputAreRejectedWithoutRecycling() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val tooWide = Bitmap.createBitmap(12001, 32, Bitmap.Config.ARGB_8888)
        try {
            PaddleTextDetector(context).use { detector ->
                val crossing = detection(listOf(PointF(.1f, .1f), PointF(.9f, .9f), PointF(.9f, .1f), PointF(.1f, .9f)))
                try { detector.rectify(source, crossing); fail("Crossing quad accepted") } catch (_: IllegalArgumentException) { }
                try { detector.detect(tooWide); fail("Oversized source accepted") } catch (_: IllegalArgumentException) { }
                assertFalse(source.isRecycled); assertFalse(tooWide.isRecycled)
            }
        } finally { source.recycle(); tooWide.recycle() }
    }

    @Test fun cancellationRejectsNewWorkAndPreservesCallerBitmap() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            PaddleTextDetector(context).use { detector ->
                detector.cancel()
                try { detector.detect(source); fail("Cancelled detector accepted work") } catch (_: CancellationException) { }
                assertFalse(source.isRecycled)
            }
        } finally { source.recycle() }
    }

    @Test fun nativeCancellationStopsOwnerAndPreservesSourcePixels() {
        val source = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val before = hash(source)
        val detector = PaddleTextDetector(context)
        val failure = AtomicReference<Throwable?>()
        val activeField = PaddleTextDetector::class.java.getDeclaredField("activeRun").apply { isAccessible = true }
        val lockField = PaddleTextDetector::class.java.getDeclaredField("lifecycleLock").apply { isAccessible = true }
        val lifecycle = lockField.get(detector)
        val owner = Thread({
            try { detector.detect(source) } catch (error: Throwable) { failure.set(error) }
            finally { try { detector.close() } catch (error: Throwable) { failure.compareAndSet(null, error) } }
        }, "independent-detector-native-cancel").apply { isDaemon = true }
        owner.start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var active = false
            while (owner.isAlive && SystemClock.elapsedRealtime() < deadline) {
                active = synchronized(lifecycle) { activeField.get(detector) != null }
                if (active) break
                Thread.sleep(1)
            }
            assertTrue("No native detection became active: ${failure.get()}", active)
            detector.cancel()
            owner.join(5000)
            assertFalse("Detector still owns native work after cancellation", owner.isAlive)
            assertTrue("Expected cancellation, got ${failure.get()}", failure.get() is CancellationException)
            assertFalse(source.isRecycled)
            assertEquals(before, hash(source))
        } finally {
            detector.cancel(); owner.join(5000)
            if (!owner.isAlive) source.recycle()
        }
    }

    private fun detection(points: List<PointF>) = PaddleTextDetector.Detection(points, .99f,
        OcrLine("", points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }))

    private fun hash(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width)
        val bytes = ByteArray(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            pixels.forEachIndexed { i, value -> for (c in 0..3) bytes[i * 4 + c] = (value ushr (c * 8)).toByte() }
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

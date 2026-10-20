package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IndependentOcrRegionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preparedCropMatchesDirectTesseractAndRemainsOwnedByCaller() {
        val bitmap = fixture()
        val before = pixels(bitmap)
        try {
            IndependentOcr(context).use { reader ->
                val first = reader.recognizeRegion(bitmap)
                assertEquals(2, first.size)
                assertTrue(first.all { it.size >= 3 })
                val expected = direct(bitmap)
                assertObservations(expected, first)
                // Reuse the same native engine across the two entry points. The
                // whole-page path owns only its temporary padded/scaled bitmap.
                assertEquals(2, reader.recognize(bitmap).size)
                assertObservations(expected, reader.recognizeRegion(bitmap))
                assertFalse(bitmap.isRecycled)
                assertArrayEquals(before, pixels(bitmap))
            }
            assertFalse(bitmap.isRecycled)
            assertArrayEquals(before, pixels(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun cancelledReaderRejectsRegionalReadWithoutTakingBitmapOwnership() {
        val bitmap = fixture()
        val before = pixels(bitmap)
        try {
            IndependentOcr(context).use { reader ->
                reader.cancel()
                try {
                    reader.recognizeRegion(bitmap)
                    fail("A cancelled reader must not start another read")
                } catch (_: InterruptedException) { }
                assertFalse(bitmap.isRecycled)
                assertArrayEquals(before, pixels(bitmap))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun singleLineModesMatchNativeAndDoNotLeakIntoRegionalReads() {
        val source = fixture()
        val bitmap = Bitmap.createBitmap(source, 0, 130, source.width, 130)
        source.recycle()
        val before = pixels(bitmap)
        try {
            IndependentOcr(context).use { reader ->
                val actual = reader.recognizeLine(bitmap)
                assertEquals(2, actual.size)
                assertTrue(actual.all { it.isNotEmpty() })
                assertObservations(direct(bitmap, listOf(
                    TessBaseAPI.PageSegMode.PSM_SINGLE_LINE to 0,
                    TessBaseAPI.PageSegMode.PSM_RAW_LINE to 0)), actual)
                assertObservations(direct(bitmap), reader.recognizeRegion(bitmap))
                assertArrayEquals(before, pixels(bitmap))
                reader.cancel()
                try { reader.recognizeLine(bitmap); fail("Cancelled line reader continued") }
                catch (_: InterruptedException) { }
                assertFalse(bitmap.isRecycled)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun closedReaderRejectsRegionalReadWithoutTakingBitmapOwnership() {
        val bitmap = fixture()
        val reader = IndependentOcr(context)
        try {
            reader.close()
            try {
                reader.recognizeRegion(bitmap)
                fail("A closed reader must not start another read")
            } catch (_: IllegalStateException) { }
            assertFalse(bitmap.isRecycled)
        } finally { reader.close(); bitmap.recycle() }
    }

    private fun fixture(): Bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.density = Bitmap.DENSITY_NONE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("REGISTER 1", 92f, 100f, paint)
            drawText("TOTAL " + '$' + "0.70", 92f, 200f, paint)
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    /** An untransformed reference read detects accidental regional padding/scale or PSM changes. */
    private fun direct(bitmap: Bitmap, passes: List<Pair<Int, Int>> = listOf(
        TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to 0,
        TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK to 2)): List<List<OcrLine>> {
        val tess = TessBaseAPI()
        try {
            val data = File(context.filesDir, "independent-ocr")
            check(tess.init(data.absolutePath, "eng+spa", TessBaseAPI.OEM_LSTM_ONLY,
                mapOf("load_system_dawg" to "0", "load_freq_dawg" to "0")))
            return passes.map { (mode, threshold) ->
                check(tess.setVariable("thresholding_method", threshold.toString()))
                tess.pageSegMode = mode
                tess.setImage(bitmap)
                tess.getHOCRText(0)
                val words = mutableListOf<OcrLine>()
                val iterator = tess.resultIterator
                if (iterator != null) try {
                    iterator.begin()
                    do {
                        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                        val text = iterator.getUTF8Text(level)
                        if (!text.isNullOrBlank()) {
                            val box = iterator.getBoundingRect(level)
                            words += OcrLine(text,
                                box.left.toFloat() / bitmap.width, box.top.toFloat() / bitmap.height,
                                box.right.toFloat() / bitmap.width, box.bottom.toFloat() / bitmap.height,
                                confidence = iterator.confidence(level) / 100f)
                        }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                } finally { iterator.delete() }
                tess.clear()
                words
            }
        } finally { tess.recycle() }
    }

    private fun assertObservations(expected: List<List<OcrLine>>, actual: List<List<OcrLine>>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedPass, actualPass) ->
            assertEquals(expectedPass.size, actualPass.size)
            expectedPass.zip(actualPass).forEach { (a, b) ->
                assertEquals(a.text, b.text)
                assertEquals(a.left, b.left, 0.000001f)
                assertEquals(a.top, b.top, 0.000001f)
                assertEquals(a.right, b.right, 0.000001f)
                assertEquals(a.bottom, b.bottom, 0.000001f)
                assertEquals(a.confidence!!, b.confidence!!, 0.000001f)
            }
        }
    }
}

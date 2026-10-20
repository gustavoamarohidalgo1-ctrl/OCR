package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineIlluminationInstrumentedTest {
    @Test fun resultIsIndependentCompleteRasterAndOriginalRemainsUnchanged() {
        val source = Bitmap.createBitmap(137, 31, Bitmap.Config.ARGB_8888)
        val original = IntArray(source.width * source.height) { index ->
            Color.rgb((index * 17) % 256, (index * 31 + 13) % 256, (index * 7 + 43) % 256)
        }
        source.setPixels(original, 0, source.width, 0, 0, source.width, source.height)
        try {
            val normalized = LineIllumination.normalize(source)
            try {
                assertNotSame(source, normalized)
                assertEquals(source.width, normalized.width)
                assertEquals(source.height, normalized.height)
                assertEquals(Bitmap.Config.ARGB_8888, normalized.config)
                assertArrayEquals("Normalization must not edit the imported raster", original, pixels(source))
                for (pixel in pixels(normalized)) {
                    assertEquals(255, Color.alpha(pixel))
                    assertEquals(Color.red(pixel), Color.green(pixel))
                    assertEquals(Color.red(pixel), Color.blue(pixel))
                }
            } finally { normalized.recycle() }
            assertFalse("Output ownership must be independent", source.isRecycled)
            assertArrayEquals(original, pixels(source))
        } finally { source.recycle() }
    }

    @Test fun uniformWhiteAndBlackStayFiniteAndUniform() {
        for (color in listOf(Color.WHITE, Color.BLACK, Color.rgb(80, 80, 80))) {
            val source = Bitmap.createBitmap(31, 19, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try {
                val normalized = LineIllumination.normalize(source)
                try {
                    val levels = pixels(normalized).map { Color.red(it) }
                    assertTrue(levels.isNotEmpty())
                    if (color == Color.BLACK) assertTrue("Black division must remain zero", levels.all { it == 0 })
                    else assertTrue("Uniform nonzero illumination must normalize to white", levels.all { it in 254..255 })
                    assertTrue(pixels(source).all { it == color })
                } finally { normalized.recycle() }
            } finally { source.recycle() }
        }
    }

    @Test fun smoothShadowIsReducedWhileEveryBlackGlyphAndPunctuationPixelIsPreserved() {
        val width = 640
        val height = 96
        val original = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val glyph = y in 24..72 && (x in 144..151 || x in 312..319 || x in 472..479)
            val punctuation = x in 334..335 && y in 68..69
            if (glyph || punctuation) Color.BLACK else {
                val level = 60 + 150 * x / (width - 1)
                Color.rgb(level, level, level)
            }
        }
        val source = Bitmap.createBitmap(original, width, height, Bitmap.Config.ARGB_8888)
        try {
            val normalized = LineIllumination.normalize(source)
            try {
                val output = pixels(normalized)
                val sampleXs = listOf(64, 320, 576)
                val before = sampleXs.map { Color.red(original[8 * width + it]) }
                val after = sampleXs.map { Color.red(output[8 * width + it]) }
                assertTrue("Fixture must contain a substantial smooth shadow", before.max() - before.min() >= 100)
                assertTrue("Background variation should be substantially reduced: $after", after.max() - after.min() <= 10)
                assertTrue("Paper background should remain bright: $after", after.all { it >= 235 })
                for (index in original.indices) {
                    assertEquals("Black ink locations must survive, including tiny punctuation at $index",
                        original[index] == Color.BLACK, output[index] == Color.BLACK)
                }
                assertArrayEquals(original, pixels(source))
            } finally { normalized.recycle() }
        } finally { source.recycle() }
    }

    @Test fun rejectsOversizedAndRecycledInputsWithoutTakingOwnership() {
        val oversized = Bitmap.createBitmap(2001, 2000, Bitmap.Config.ARGB_8888)
        oversized.setPixel(0, 0, Color.RED)
        val recycled = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { recycle() }
        try {
            try { LineIllumination.normalize(oversized); fail("Accepted a raster larger than 4MP") }
            catch (_: IllegalArgumentException) { }
            assertFalse(oversized.isRecycled)
            assertEquals(Color.RED, oversized.getPixel(0, 0))
            try { LineIllumination.normalize(recycled); fail("Accepted a recycled raster") }
            catch (_: IllegalArgumentException) { }
        } finally { oversized.recycle() }
    }

    @Test fun tallNarrowImageKeepsAllPixelsWithBoundedBackgroundEstimator() {
        val source = Bitmap.createBitmap(32, 12000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        source.setPixel(12, 11000, Color.BLACK)
        try {
            val result = LineIllumination.normalize(source)
            try {
                assertEquals(32, result.width)
                assertEquals(12000, result.height)
                assertEquals(Color.BLACK, result.getPixel(12, 11000))
                assertEquals(Color.GRAY, source.getPixel(0, 0))
            } finally { result.recycle() }
        } finally { source.recycle() }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}

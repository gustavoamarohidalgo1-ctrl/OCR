package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PaddleTextDetectorRasterTest {
    private val v6 = PaddleTextDetector.Profile.V6_SMALL

    private fun assertWithinBudget(size: Pair<Int, Int>, source: Pair<Int, Int>) {
        val (width, height) = size
        assertEquals("$source -> $size", 0, width % 32)
        assertEquals("$source -> $size", 0, height % 32)
        assertTrue("$source -> $size", width in 32..v6.maxRasterSide && height in 32..v6.maxRasterSide)
        assertTrue("$source -> $size", width.toLong() * height <= v6.maxRasterPixels)
    }

    @Test fun tabletPhotosAboveFourMegapixelsAreReducedByAreaInsteadOfRejected() {
        // 10 MP 3:4 camera photo (InvoiceWorker decodes up to 10 MP) and a 4.3 MP raster.
        for ((source, expected) in listOf(
            (2736 to 3648) to (1728 to 2304),
            (3648 to 2736) to (2304 to 1728),
            (2400 to 1800) to (2304 to 1728),
            (3000 to 4000) to (1728 to 2304),
        )) {
            val size = PaddleTextDetector.rasterSize(v6, source.first, source.second)
            assertEquals("$source", expected, size)
            assertWithinBudget(size, source)
        }
    }

    @Test fun pagesWithinBudgetKeepTheModelResizeRules() {
        // Unchanged DetResizeForTest results for the sizes of the bundled test photos.
        assertEquals(736 to 992, PaddleTextDetector.rasterSize(v6, 750, 1000))
        assertEquals(992 to 736, PaddleTextDetector.rasterSize(v6, 1000, 750))
        assertEquals(1216 to 1600, PaddleTextDetector.rasterSize(v6, 1200, 1600))
        // Short sides are still enlarged to 736 and long receipts limited to 4000.
        assertEquals(736 to 992, PaddleTextDetector.rasterSize(v6, 600, 800))
        assertEquals(512 to 4000, PaddleTextDetector.rasterSize(v6, 1000, 8000))
    }

    @Test fun everyAcceptedSourceSizeFitsTheBudgetAndKeepsItsAspectRatio() {
        for (width in 300..9000 step 97) for (height in 300..9000 step 89) {
            if (width.toLong() * height > 12_000_000L) continue
            val size = PaddleTextDetector.rasterSize(v6, width, height)
            assertWithinBudget(size, width to height)
            val sourceRatio = width.toDouble() / height
            val error = abs(size.first.toDouble() / size.second - sourceRatio) / sourceRatio
            assertTrue("$width x $height -> $size", error <= 64.0 / minOf(size.first, size.second))
        }
    }

    @Test fun mobileProfileIsUnchanged() {
        assertEquals(1024 to 768, PaddleTextDetector.rasterSize(PaddleTextDetector.Profile.V5_MOBILE, 2400, 1800))
        assertEquals(768 to 1024, PaddleTextDetector.rasterSize(PaddleTextDetector.Profile.V5_MOBILE, 2736, 3648))
    }
}

package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodeSizingTest {
    @Test fun smallImagesRetainTheirOriginalResolution() {
        val plan = ImageDecodeSizing.plan(1600, 2100, 10_000_000, 12000)
        assertEquals(1600, plan.width)
        assertEquals(2100, plan.height)
        assertEquals(1, plan.sampleSize)
        assertFalse(plan.scales)
    }

    @Test fun twelveMegapixelPhotoKeepsAlmostTenInsteadOfThreeMegapixels() {
        val plan = ImageDecodeSizing.plan(4000, 3000, 10_000_000, 12000)
        assertEquals(1, plan.sampleSize)
        assertTrue(plan.pixels in 9_990_000L..10_000_000L)
        assertTrue(plan.pixels > 3L * (2000 * 1500))
        assertTrue(plan.intermediatePixels + plan.pixels <= 30_000_000)
    }

    @Test fun fiftyMegapixelPhotoSamplesBeforeScalingWithoutLargePeak() {
        val plan = ImageDecodeSizing.plan(10000, 5000, 10_000_000, 12000)
        assertEquals(2, plan.sampleSize)
        assertTrue(plan.pixels in 9_990_000L..10_000_000L)
        assertTrue(plan.intermediatePixels + plan.pixels <= 30_000_000)
    }

    @Test fun decoderIntermediateAlsoRespectsTheAllocationReserve() {
        val plan = ImageDecodeSizing.plan(6000, 6000, 10_000_000, 12000)
        assertEquals(2, plan.sampleSize)
        assertEquals(9_000_000, plan.pixels)
        assertTrue(plan.intermediatePixels + plan.pixels <= 30_000_000)
    }

    @Test fun longReceiptRespectsSideLimitWithoutHalvingUnnecessarily() {
        val plan = ImageDecodeSizing.plan(1000, 13000, 10_000_000, 12000)
        assertTrue(plan.pixels in 9_990_000L..10_000_000L)
        assertTrue(plan.height <= 12000)
        val sideLimited = ImageDecodeSizing.plan(300, 13000, 10_000_000, 12000)
        assertEquals(12000, sideLimited.height)
        assertTrue(sideLimited.width in 276..277)
    }

    @Test fun variedDimensionsRespectPixelsSidesAndDecoderPeak() {
        val dimensions = listOf(1 to 100_000_000, 100_000_000 to 1, 1025 to 2049,
            4001 to 3001, 9999 to 9999, 12001 to 700, 317 to 411)
        for ((width, height) in dimensions) for (budget in listOf(1_000_000L, 4_194_304L, 10_000_000L)) {
            val plan = ImageDecodeSizing.plan(width, height, budget, 12000)
            assertTrue(plan.width > 0 && plan.height > 0)
            assertTrue(plan.width <= width && plan.height <= height)
            assertTrue(plan.pixels <= budget)
            assertTrue(maxOf(plan.width, plan.height) <= 12000)
            assertTrue(plan.intermediatePixels + plan.pixels <= budget * 3)
            assertTrue(plan.targetDensity <= plan.sourceDensity)
        }
    }
}

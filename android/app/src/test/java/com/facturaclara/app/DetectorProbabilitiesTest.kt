package com.facturaclara.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DetectorProbabilitiesTest {
    @Test fun preservesValidProbabilitiesExactly() {
        val values = floatArrayOf(0f, .2f, .3f, .45f, .6f, 1f)
        val expected = values.copyOf()
        val summary = DetectorProbabilities.validateInPlace(values)
        assertArrayEquals(expected, values, 0f)
        assertEquals(0f, summary.rawMinimum, 0f); assertEquals(1f, summary.rawMaximum, 0f)
        assertEquals(0, summary.clampedCount)
    }

    @Test fun recordsAndClampsOnlyTinyFloatOvershoot() {
        val low = -1e-7f; val high = 1.0000001f
        val values = floatArrayOf(low, .3f, high)
        val summary = DetectorProbabilities.validateInPlace(values)
        assertArrayEquals(floatArrayOf(0f, .3f, 1f), values, 0f)
        assertEquals(low, summary.rawMinimum, 0f); assertEquals(high, summary.rawMaximum, 0f)
        assertEquals(2, summary.clampedCount)
    }

    @Test fun rejectsNonfiniteAndSubstantialOvershootWithValueAndPosition() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.00001f, 1.00001f, 255f)) {
            try { DetectorProbabilities.validateInPlace(floatArrayOf(.5f, value)); fail("Accepted $value") }
            catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("at 1:"))
                assertTrue(error.message.orEmpty().contains(value.toString()))
            }
        }
    }

    @Test fun rejectsEmptyMap() {
        try { DetectorProbabilities.validateInPlace(floatArrayOf()); fail("Accepted empty map") }
        catch (_: IllegalArgumentException) { }
    }
}

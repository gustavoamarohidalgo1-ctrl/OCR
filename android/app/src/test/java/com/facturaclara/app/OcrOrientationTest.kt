package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrOrientationTest {
    @Test fun upsideDownAnglesDoNotSplitAcrossTheSignedBoundary() {
        assertEquals(180, OcrOrientation.quarterTurn(listOf(179f, -179f, 178f, -178f)))
    }
    @Test fun quarterTurnsHaveTheCorrectCorrectionDirection() {
        assertEquals(-90, OcrOrientation.quarterTurn(listOf(89f, 90f, 91f)))
        assertEquals(90, OcrOrientation.quarterTurn(listOf(-89f, -90f, -91f)))
    }
    @Test fun mixedOrInsufficientEvidenceDoesNotRotate() {
        assertEquals(0, OcrOrientation.quarterTurn(listOf(180f, -180f)))
        assertEquals(0, OcrOrientation.quarterTurn(listOf(0f, 1f, 180f, -179f)))
        assertEquals(0, OcrOrientation.quarterTurn(listOf(Float.NaN, Float.NaN, Float.NaN)))
    }

    @Test fun clearlyTiltedPageIsStraightened() {
        assertEquals(18f, OcrOrientation.deskew(listOf(-18f, -17.5f, -18.4f, -19f, -16.8f, -18.1f)), .01f)
    }

    @Test fun smallTiltIsLeftAlone() {
        assertEquals(0f, OcrOrientation.deskew(listOf(1.2f, .8f, 1.5f, 1f, 1.1f)), 0f)
    }

    @Test fun inconsistentAnglesAreLeftAlone() {
        assertEquals(0f, OcrOrientation.deskew(listOf(-18f, 12f, 0f, 7f, -6f, 25f)), 0f)
    }

    @Test fun tooFewLinesAreLeftAlone() {
        assertEquals(0f, OcrOrientation.deskew(listOf(-18f, -18f, -18f)), 0f)
    }

    @Test fun steepLinesCountAgainstTheAgreement() {
        val angles = List(5) { -18f } + List(5) { 80f }
        assertEquals(0f, OcrOrientation.deskew(angles), 0f)
        assertEquals(18f, OcrOrientation.deskew(List(6) { -18f } + List(4) { 80f }), .01f)
    }

    @Test fun ticketTurnedThirtyFiveDegreesIsStraightened() {
        assertEquals(35f, OcrOrientation.deskew(listOf(-35f, -34f, -36f, -35.5f, -34.5f)), .01f)
    }
}

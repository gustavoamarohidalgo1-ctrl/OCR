package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrConsistencyTest {
    @Test
    fun aChangedLetterIsDetectedEvenWhenAllNumbersMatch() {
        val result = OcrConsistency.compare("Empresa Clara TOTAL 118.00", "Empresa Clora TOTAL 118.00")
        assertFalse(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun aChangedDigitIsDetectedByBothChecks() {
        val result = OcrConsistency.compare("TOTAL 118.00", "TOTAL 119.00")
        assertFalse(result.contentMatches)
        assertFalse(result.numbersMatch)
    }

    @Test
    fun layoutSpacingAndSpacingAroundNumericSeparatorsAreTolerated() {
        val result = OcrConsistency.compare(
            "Fecha: 17/10/2026\nTOTAL: S/ 118.00",
            "Fecha:\t17 / 10/ 2026\nTOTAL:   S/ 118. 00",
        )
        assertTrue(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun losingOneRepeatedQuantityIsDetected() {
        val result = OcrConsistency.compare("Papel 2 10.00\nLápiz 2 10.00", "Papel 2 10.00\nLápiz 10.00")
        assertFalse(result.contentMatches)
        assertFalse(result.numbersMatch)
    }

    @Test
    fun changingPunctuationIsNotTreatedAsLayout() {
        val result = OcrConsistency.compare("TOTAL: 118.00", "TOTAL; 118.00")
        assertFalse(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun unicodeCompositionAndNonbreakingSpacesDoNotCreateFalseDifferences() {
        val result = OcrConsistency.compare("Lápiz\u00a02\u00a010.00", "La\u0301piz 2 10.00")
        assertTrue(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun adjacentQuantitiesAreNotSilentlyConcatenated() {
        val result = OcrConsistency.compare("Cantidades 1 2", "Cantidades 12")
        assertFalse(result.contentMatches)
        assertFalse(result.numbersMatch)
    }

    @Test
    fun mergedWordsAreNotTreatedAsLayoutEvenWhenLettersAndNumbersMatch() {
        val result = OcrConsistency.compare("DE LA CRUZ TOTAL 118.00", "DELA CRUZ TOTAL 118.00")
        assertFalse(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun splitProductCodeIsNotAcceptedAsAnExactReading() {
        val result = OcrConsistency.compare("Codigo AB12 Cantidad 4", "Codigo AB 12 Cantidad 4")
        assertFalse(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun wordWrapAndPunctuationSpacingDoNotChangeWords() {
        val result = OcrConsistency.compare("Papel bond A4, paquete de 500 hojas", "Papel   bond\nA4 , paquete\u00a0de 500 hojas")
        assertTrue(result.contentMatches)
        assertTrue(result.numbersMatch)
    }

    @Test
    fun confidenceBelowPointFiveAndInvalidScoresAreSevere() {
        assertEquals(OcrConsistency.ConfidenceLevel.SEVERE, OcrConsistency.confidenceLevel(listOf(.49f), listOf(.95f)))
        assertEquals(OcrConsistency.ConfidenceLevel.SEVERE, OcrConsistency.confidenceLevel(listOf(.95f), listOf(Float.NaN)))
        assertEquals(OcrConsistency.ConfidenceLevel.SEVERE, OcrConsistency.confidenceLevel(listOf(Float.POSITIVE_INFINITY), emptyList()))
    }

    @Test
    fun moderateConfidenceIsCautionInsteadOfAnAccuracyClaim() {
        assertEquals(OcrConsistency.ConfidenceLevel.CAUTION, OcrConsistency.confidenceLevel(listOf(.80f), listOf(.84f)))
        assertEquals(OcrConsistency.ConfidenceLevel.CAUTION, OcrConsistency.confidenceLevel(listOf(.71f), listOf(.95f)))
        assertEquals(OcrConsistency.ConfidenceLevel.CAUTION, OcrConsistency.confidenceLevel(listOf(.50f), emptyList()))
        assertEquals(OcrConsistency.ConfidenceLevel.CLEAR, OcrConsistency.confidenceLevel(listOf(.72f), listOf(.85f)))
    }
}

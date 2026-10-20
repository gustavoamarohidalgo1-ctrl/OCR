package com.facturaclara.app

import org.junit.Assert.*
import org.junit.Test

class RegionConsensusTest {
    private fun line(text: String, confidence: Float = .9f) =
        listOf(OcrLine(text, .1f, .2f, .9f, .25f, confidence = confidence))

    @Test fun twoMatchingReadingsCannotHideTextDetectedByAThird() {
        assertNull(RegionConsensus.whole(listOf(line("Subtotal 20.00"), line("Subtotal 20.00"), line("Subtotal $ 20.00"))))
    }
    @Test fun twoCompleteReadingsCanOutweighOneThatOnlyOmitsContent() {
        val full = line("Subtotal $ 20.00")
        assertEquals(full, RegionConsensus.whole(listOf(full, full, line("Subtotal 20.00"))))
    }
    @Test fun recoveryNeedsTwoRegionReadingsAndWholeImageSupport() {
        val full = line("Subtotal $ 20.00")
        val readings = listOf(line("Subtotal 20.00"), line("Subtotal 20.00"), full)
        assertEquals(full, RegionConsensus.recovered(readings, listOf(full, full)))
        assertNull(RegionConsensus.recovered(readings, listOf(full, line("Subtotal 20.00"))))
        assertNull(RegionConsensus.recovered(readings, listOf(line("Subtotal $ $ 20.00"), line("Subtotal $ $ 20.00"))))
        assertNull(RegionConsensus.recovered(readings, listOf(line("Subtotal 20.00"), line("Subtotal 20.00"))))
    }
    @Test fun cropsCannotReplaceTextAlreadyStronglyConfirmedInTwoWholeReadings() {
        val correct = line("FOOD")
        val altered = line("FO0D")
        assertNull(RegionConsensus.recovered(listOf(correct, correct, altered), listOf(altered, altered)))
        assertNull(RegionConsensus.recovered(listOf(line("FOOD $ 10.00"), line("FOOD 10.00"),
            line("FO0D $ 10.00")), listOf(line("FO0D $ 10.00"), line("FO0D $ 10.00"))))
    }
    @Test fun lowConfidenceWordsAndConflictingCandidatesRemainUnresolved() {
        val weak = line("LUXCAFE", .55f)
        assertNull(RegionConsensus.recovered(listOf(weak), listOf(weak, weak)))
        val a = line("10.00")
        val b = line("18.00")
        assertNull(RegionConsensus.recovered(listOf(a, b), listOf(a, a, b, b)))
    }
    @Test fun repeatedConfidentRegionalContradictionBlocksEvenWithoutWholeSupport() {
        val first = line("Total 12.00")
        val conflicting = line("Total 13.00")
        assertNull(RegionConsensus.recovered(listOf(line("Total 12.00", .4f)),
            listOf(first, first, conflicting, conflicting, conflicting)))
    }
    @Test fun tokenBoundariesAndRepeatedAmountsSurviveInclusionChecks() {
        assertFalse(RegionConsensus.includes("DELA 10.00", "DE LA 10.00"))
        assertFalse(RegionConsensus.includes("10.00", "10.00 10.00"))
        assertFalse(RegionConsensus.includes("Food", "FOOD"))
    }
}

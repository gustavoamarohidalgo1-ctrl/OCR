package com.facturaclara.app

import com.facturaclara.app.OpticalCellConsensus.Family
import com.facturaclara.app.OpticalCellConsensus.Observation
import org.junit.Assert.*
import org.junit.Test

class FallbackEvidenceGuardTest {
    @Test fun faithfulLegacyOutputSurvivesDifferentCellSegmentation() {
        val coffee = evidence(box("", .10f, .30f, .45f), confirmed("Coffee"))
        val price = evidence(box("", .70f, .30f, .85f), confirmed("2.95"))
        assertTrue(inspect(listOf(coffee, price), listOf(box("Coffee 2.95", .10f, .30f, .85f))).isEmpty())
    }

    @Test fun oneConfirmedLineCanBeRetainedAsMultipleLegacyFragments() {
        val cell = evidence(box("", .10f, .30f, .85f), confirmed("Coffee 2.95"))
        assertTrue(inspect(listOf(cell), listOf(box("Coffee", .10f, .30f, .45f), box("2.95", .70f, .30f, .85f))).isEmpty())
    }

    @Test fun tallSlopingEnvelopeUsesItsOwnBaselineInsteadOfBridgingTwoHeadings() {
        // Geometry from the 08 regression; anonymous text keeps this a layout
        // test, not a recognition rule or expected-photo transcription.
        val first = OcrLine("", .34133333f, .096f, .51599997f, .15099999f, -.02325582f)
        val second = OcrLine("", .192f, .134f, .7413333f, .207f, -.03850855f)
        val cells = listOf(evidence(first, confirmed("HEADING")), evidence(second, confirmed("SECOND ROW")))
        val legacy = listOf(
            OcrLine("HEADING", .35466668f, .106f, .50666666f, .146f),
            OcrLine("SECOND", .20266667f, .153f, .504f, .201f, -.041950736f),
            OcrLine("ROW", .56f, .144f, .728f, .186f, -.041950736f),
        )
        assertTrue(inspect(cells, legacy).isEmpty())
        assertReason("contradicted_confirmed_evidence", inspect(cells,
            listOf(legacy[0].copy(text = "ALTERED")) + legacy.drop(1)))
    }

    @Test fun equallyCloseOverlappingCellsRemainAmbiguousAfterNearestBaselineFiltering() {
        val cells = listOf(evidence(box(), confirmed("FIRST")), evidence(box(), confirmed("SECOND")))
        assertReason("ambiguous_fallback_geometry", inspect(cells, listOf(box("FIRST"))))
    }

    @Test fun legacyCannotRevertAnIndependentlyConfirmedSpelling() {
        val cell = evidence(box(), confirmed("FAIR OAKS"))
        assertReason("contradicted_confirmed_evidence", inspect(listOf(cell), listOf(box("FAIR 0AKS"))))
    }

    @Test fun qualifiedAdditionalQuantityCannotDisappearOnFallback() {
        val cell = evidence(box(), listOf(ml("Item")) + paddle("1 Item"))
        assertNull(cell.decision.text)
        assertReason("omitted_consulted_tokens", inspect(listOf(cell), listOf(box("Item"))))
        assertTrue(inspect(listOf(cell), listOf(box("1 Item"))).isEmpty())
    }

    @Test fun weakCompleteMlQuantityCannotDisappearOnFallback() {
        val observations = listOf(ml("1 Item", .10f), tess("Item")) + paddle("Item")
        // IndependentPageConsensus vetoes this otherwise available short quorum.
        val base = OpticalCellConsensus.choose(observations)
        val cell = FallbackEvidenceGuard.CellEvidence(box(), observations,
            base.copy(text = null, blockers = base.blockers + "omitted_context_tokens"))
        assertReason("omitted_consulted_tokens", inspect(listOf(cell), listOf(box("Item"))))
    }

    @Test fun additionalQuantityIsProtectedEvenInsideACoarseLegacyLine() {
        val item = evidence(box("", .10f, .30f, .45f), listOf(ml("Item")) + paddle("1 Item"))
        val price = evidence(box("", .70f, .30f, .85f), confirmed("2.95"))
        assertReason("omitted_consulted_tokens", inspect(listOf(item, price), listOf(box("Item 2.95", .10f, .30f, .85f))))
        assertTrue(inspect(listOf(item, price), listOf(box("1 Item 2.95", .10f, .30f, .85f))).isEmpty())
    }

    @Test fun literalNumericGroupingConflictPersistsAfterLegacySelection() {
        val cell = evidence(box(), listOf(ml("2 / 60.00"), tess("2/60.00")) + paddle("2/60.00"))
        assertReason("conflicting_numeric_grouping", inspect(listOf(cell), listOf(box("2/60.00"))))
    }

    @Test fun weakCompleteMlNumericGroupingCannotBeDiscarded() {
        val observations = listOf(ml("2 / 60.00", .10f), tess("2/60.00")) + paddle("2/60.00")
        val cell = FallbackEvidenceGuard.CellEvidence(box(), observations,
            OpticalCellConsensus.choose(observations).copy(text = null, blockers = listOf("conflicting_numeric_grouping")))
        assertReason("conflicting_numeric_grouping", inspect(listOf(cell), listOf(box("2/60.00"))))
    }

    @Test fun differentWeakStringDoesNotVetoExistingQuorum() {
        val observations = listOf(ml("ltern", .10f))
        val cell = evidence(box(), observations)
        assertTrue(inspect(listOf(cell), listOf(box("Item"))).isEmpty())
    }

    @Test fun singleFamilyDifferentSpellingDoesNotCreateANewQuorumRule() {
        val cell = evidence(box(), paddle("OAKS"))
        assertNull(cell.decision.text)
        assertTrue(inspect(listOf(cell), listOf(box("0AKS"))).isEmpty())
    }

    @Test fun repeatedConfirmedAmountsCannotBorrowOneLegacyOccurrence() {
        val first = evidence(box("", .10f, .30f, .25f), confirmed("2.95"))
        val second = evidence(box("", .60f, .30f, .75f), confirmed("2.95"))
        val conflicts = inspect(listOf(first, second), listOf(box("2.95", .10f, .30f, .75f)))
        assertReason("omitted_consulted_occurrence", conflicts)
    }

    @Test fun repeatedRowsCannotBorrowEachOthersQuantity() {
        val first = evidence(box("", .10f, .30f, .85f), confirmed("1 Item 2.95"))
        val second = evidence(box("", .10f, .40f, .85f), listOf(ml("Item 2.95")) + paddle("1 Item 2.95"))
        val legacy = listOf(box("1 Item 2.95", .10f, .30f, .85f), box("Item 2.95", .10f, .40f, .85f))
        val conflicts = inspect(listOf(first, second), legacy)
        assertTrue(conflicts.any { it.cellIndices == listOf(1) && it.reason == "omitted_consulted_tokens" })
    }

    @Test fun confirmedCellWithNoLegacyOwnerIsNeverSilentlyIgnored() {
        val cell = evidence(box("", .10f, .30f, .30f), confirmed("1"))
        assertReason("unmapped_consulted_evidence", inspect(listOf(cell), listOf(box("Item", .60f, .70f, .85f))))
    }

    @Test fun weakContextQuantityWithNoNewOwnerCannotDisappear() {
        val cell = evidence(box("", .10f, .30f, .30f), listOf(ml("1", .10f)))
        assertNull(cell.decision.text)
        assertReason("unmapped_consulted_evidence", inspect(listOf(cell), listOf(box("Item", .60f, .70f, .85f))))
    }

    @Test fun weakContextQuantityWithAmbiguousRowsStillBlocks() {
        val cell = evidence(OcrLine("", .10f, .30f, .85f, .39f), listOf(ml("1", .10f)))
        val legacy = listOf(box("1", .10f, .30f, .85f), box("Item", .10f, .36f, .85f))
        assertReason("ambiguous_fallback_geometry", inspect(listOf(cell), legacy))
    }

    @Test fun blankDetectorProposalAloneDoesNotVetoLegacy() {
        val cell = evidence(box("", .10f, .30f, .30f), emptyList())
        assertTrue(inspect(listOf(cell), listOf(box("Item", .60f, .70f, .85f))).isEmpty())
    }

    @Test fun connectedRegionSpanningTwoPhysicalRowsCannotBorrowEvidence() {
        val cell = evidence(OcrLine("", .10f, .30f, .85f, .39f), confirmed("1 Item"))
        val legacy = listOf(box("1", .10f, .30f, .85f), box("Item", .10f, .36f, .85f))
        assertReason("ambiguous_fallback_geometry", inspect(listOf(cell), legacy))
    }

    @Test fun belowThresholdPaddleExtraTokenDoesNotBecomeQualifiedEvidence() {
        val weak = Observation("1 Item", .949f, "weak-paddle", Family.PADDLE, .99f)
        val cell = evidence(box(), listOf(ml("Item"), weak))
        assertTrue(inspect(listOf(cell), listOf(box("Item"))).isEmpty())
    }

    @Test fun qualifiedSinglePaddleRasterStillProtectsAdditionalToken() {
        val one = Observation("1 Item", .95f, "one-raster", Family.PADDLE, .75f)
        val cell = evidence(box(), listOf(ml("Item"), one))
        assertReason("omitted_consulted_tokens", inspect(listOf(cell), listOf(box("Item"))))
    }

    private fun evidence(line: OcrLine, observations: List<Observation>) =
        FallbackEvidenceGuard.CellEvidence(line, observations, OpticalCellConsensus.choose(observations))
    private fun inspect(evidence: List<FallbackEvidenceGuard.CellEvidence>, fallback: List<OcrLine>) =
        FallbackEvidenceGuard.inspect(evidence, fallback)
    private fun assertReason(reason: String, conflicts: List<FallbackEvidenceGuard.Conflict>) =
        assertTrue("Expected $reason, got $conflicts", conflicts.any { it.reason == reason })
    private fun ml(text: String, confidence: Float = .95f) = Observation(text, confidence, "ml", Family.ML)
    private fun tess(text: String) = Observation(text, .95f, "tess", Family.TESSERACT)
    private fun paddle(text: String) = listOf("a", "b").map { Observation(text, .99f, it, Family.PADDLE, .97f) }
    private fun confirmed(text: String) = listOf(ml(text)) + paddle(text)
    private fun box(text: String = "", left: Float = .10f, top: Float = .30f, right: Float = .85f) =
        OcrLine(text, left, top, right, top + .03f)
}

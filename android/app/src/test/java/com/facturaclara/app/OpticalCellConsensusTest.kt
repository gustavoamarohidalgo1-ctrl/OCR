package com.facturaclara.app

import com.facturaclara.app.OpticalCellConsensus.Family
import com.facturaclara.app.OpticalCellConsensus.Observation
import com.facturaclara.app.OpticalCellConsensus.WordConfidence
import org.junit.Assert.*
import org.junit.Test

class OpticalCellConsensusTest {
    private fun ml(text: String, confidence: Float = .9f, id: String = "ml-original") =
        Observation(text, confidence, id, Family.ML)
    private fun tess(text: String, confidence: Float = .95f, id: String = "tess-original") =
        Observation(text, confidence, id, Family.TESSERACT)
    private fun paddle(text: String, id: String, confidence: Float = .99f, minimum: Float? = .9f) =
        Observation(text, confidence, id, Family.PADDLE, minimum)
    private fun choose(vararg observations: Observation) = OpticalCellConsensus.choose(observations.toList())

    @Test fun twoDifferentFamiliesSelectAnObservedCell() {
        val result = choose(ml("Total $13.05"), tess("Total $13.05"))
        assertEquals("Total $13.05", result.text)
        assertEquals(setOf(Family.ML, Family.TESSERACT), result.supportingFamilies)
        assertTrue(result.blockers.isEmpty())
    }

    @Test fun repeatedRunsOfOneFamilyAreNotIndependentEvidence() {
        assertNull(choose(ml("OAKS"), ml("OAKS", id = "ml-contrast"), ml("OAKS", id = "ml-crop")).text)
        assertNull(choose(paddle("OAKS", "raster-a"), paddle("OAKS", "raster-b")).text)
    }

    @Test fun paddleRequiresTwoDifferentRastersEvenWhenModelsDiffer() {
        val first = paddle("OAKS", "pixels-hash-a")
        assertNull(choose(tess("OAKS"), first).text)
        assertNull(choose(tess("OAKS"), first, first.copy(confidence = .98f)).text)
        assertEquals("OAKS", choose(tess("OAKS"), first, paddle("OAKS", "pixels-hash-b")).text)
    }

    @Test fun letterDigitSubstitutionMustAlreadyExistInIndependentObservations() {
        val original = ml("FAIR 0AKS, CA")
        val result = choose(original, tess("FAIR OAKS, CA"),
            paddle("FAIR OAKS, CA", "margin"), paddle("FAIR OAKS, CA", "wide"))
        assertEquals("FAIR OAKS, CA", result.text)
        assertEquals(listOf(original), result.disagreements)
        assertEquals(setOf(Family.PADDLE, Family.TESSERACT), result.supportingFamilies)
    }

    @Test fun twoFamiliesCannotDeleteALeadingQuantitySeenByOneQualifiedReading() {
        val result = choose(ml("1 Open Food"), tess("Open Food"),
            paddle("Open Food", "crop-a"), paddle("Open Food", "crop-b"))
        assertNull(result.text)
        assertEquals(listOf("additional_observed_tokens"), result.blockers)
    }

    @Test fun extraEvidenceDoesNotNeedItsOwnFamilyQuorumToBlockDeletion() {
        val result = choose(ml("Open Food"), tess("Open Food"), paddle("1 Open Food", "single-raster"))
        assertNull(result.text)
        assertEquals(listOf("additional_observed_tokens"), result.blockers)
    }

    @Test fun repeatedAmountsCannotBeCollapsed() {
        assertNull(choose(ml("10.00 10.00"), tess("10.00"),
            paddle("10.00", "a"), paddle("10.00", "b")).text)
        assertEquals("10.00 10.00", choose(ml("10.00 10.00"), tess("10.00 10.00"),
            paddle("10.00", "a"), paddle("10.00", "b")).text)
    }

    @Test fun punctuationCannotBeDiscardedAsWhitespace() {
        assertNull(choose(ml("- American Ch"), tess("American Ch"),
            paddle("American Ch", "a"), paddle("American Ch", "b")).text)
        assertNull(choose(ml("Subtotal $ 20.00"), tess("Subtotal 20.00"),
            paddle("Subtotal 20.00", "a"), paddle("Subtotal 20.00", "b")).text)
    }

    @Test fun competingCandidatesBlockRegardlessOfNumberOfSupportingRuns() {
        val result = choose(ml("10:23AM", id = "a"), tess("10:23AM"),
            ml("10:23AM", id = "b"), ml("10:23AM", id = "c"),
            ml("10:23PM", id = "d"), paddle("10:23PM", "p-a"), paddle("10:23PM", "p-b"))
        assertNull(result.text)
        assertEquals(listOf("competing_candidates"), result.blockers)
    }

    @Test fun aSameLengthThirdFamilyDisagreementIsRecordedWithoutRewriting() {
        val differing = tess("10:23PM")
        val result = choose(ml("10:23AM"), paddle("10:23AM", "a"), paddle("10:23AM", "b"), differing)
        assertEquals("10:23AM", result.text)
        assertEquals(listOf(differing), result.disagreements)
    }

    @Test fun cellsAreNeverSynthesizedByCombiningDifferentObservations() {
        assertNull(choose(ml("- 10:23AM"), tess("10:23PM"),
            paddle("+ 10:23AM", "a"), paddle("+ 10:23AM", "b")).text)
    }

    @Test fun tokenBoundariesCaseAndOrderRemainSignificant() {
        for ((first, second) in listOf("DE LA" to "DELA", "Food" to "FOOD", "1 2" to "2 1")) {
            assertNull(choose(ml(first), tess(second)).text)
        }
    }

    @Test fun representativeIsAnExactExistingStringIncludingWhitespace() {
        val observed = "  Subtotal\t$ 20.00\n"
        val readings = listOf(ml(observed), tess("Subtotal $20.00"))
        val result = OpticalCellConsensus.choose(readings)
        assertEquals(observed, result.text)
        assertTrue(readings.any { it.text == result.text })
        assertEquals(result.text, OpticalCellConsensus.choose(readings.reversed()).text)
    }

    @Test fun quantityAndPriceCannotAgreeWithAJoinedNumericGroup() {
        assertNull(choose(ml("Papel A4 / 2 / 60.00"), tess("Papel A4/ 2/60.00")).text)
        assertNull(choose(ml("2 /60.00"), tess("2/60.00")).text)
        assertEquals("2 / 60.00", choose(ml("2 / 60.00"), tess("2 /60.00")).text)
    }

    @Test fun oneQualifiedNumericGroupingConflictBlocksAnOtherwiseSupportedCandidate() {
        val conflict = paddle("2 / 60.00", "single-raster")
        val result = choose(ml("2/60.00"), tess("2/60.00"), conflict)
        assertNull(result.text)
        assertEquals(listOf("conflicting_numeric_grouping"), result.blockers)
        assertEquals(listOf(conflict), result.disagreements)
        val reverse = choose(ml("2 / 60.00"), tess("2 /60.00"), paddle("2/60.00", "single-raster"))
        assertNull(reverse.text)
        assertEquals(listOf("conflicting_numeric_grouping"), reverse.blockers)
    }

    @Test fun numericCodesDatesTimesAndSignsKeepTheirLiteralGrouping() {
        for ((first, second) in listOf("F123-456789" to "F123 -456789",
            "17/10/2026" to "17 /10/2026", "10:23" to "10 :23", "-2.00" to "- 2.00",
            ".17" to ". 17", "12.20" to "12 .20", "١٧/١٠/٢٠٢٦" to "١٧ /١٠/٢٠٢٦")) {
            assertNull("$first must differ from $second", choose(ml(first), tess(second)).text)
            assertEquals(first, choose(ml(first), tess(first)).text)
        }
    }

    @Test fun spacesBesideCurrencyAndNonNumericPunctuationRemainPresentationOnly() {
        for ((first, second) in listOf("$ 13.05" to "$13.05", "Total: $ 13.05" to "Total :$13.05",
            "Subtotal: S/ 100.00" to "Subtotal:S/100.00", "CARL 'S JR" to "CARL'S JR")) {
            assertEquals(first, choose(ml(first), tess(second)).text)
        }
    }

    @Test fun weakNumericGroupingConflictIsRecordedButDoesNotVote() {
        val weak = ml("2 / 60.00", .4f)
        val result = choose(weak, tess("2/60.00"), paddle("2/60.00", "a"), paddle("2/60.00", "b"))
        assertEquals("2/60.00", result.text)
        assertEquals(listOf(weak), result.disagreements)
    }

    @Test fun mlPerWordScoresCannotBeBorrowedAcrossDifferentNumericGrouping() {
        val mismatched = ml("2/60.00").copy(wordConfidences = listOf(
            WordConfidence("2", .9f), WordConfidence("/", .9f), WordConfidence("60.00", .9f)))
        assertNull(choose(mismatched, tess("2/60.00")).text)
        assertEquals("2 / 60.00", choose(mismatched.copy(text = "2 / 60.00"), tess("2 /60.00")).text)
    }

    @Test fun canonicalUnicodeIsComparedButTheReturnedTextIsNotNormalized() {
        val decomposed = "Cafe\u0301"
        assertEquals(decomposed, choose(ml(decomposed), tess("Café")).text)
    }

    @Test fun mlLettersAndNumbersUseTheHigherThreshold() {
        for (value in listOf("Food", "1")) {
            assertNull(choose(ml(value, .649f), tess(value)).text)
            assertEquals(value, choose(ml(value, .65f), tess(value)).text)
        }
        assertNull(choose(ml("-", .499f), tess("-")).text)
        assertEquals("-", choose(ml("-", .50f), tess("-")).text)
    }

    @Test fun tessAndPaddleMustSatisfyTheirOwnThresholds() {
        assertNull(choose(ml("Food"), tess("Food", .849f)).text)
        assertEquals("Food", choose(ml("Food"), tess("Food", .85f)).text)
        assertNull(choose(ml("Food"), paddle("Food", "a", .949f), paddle("Food", "b")).text)
        assertNull(choose(ml("Food"), paddle("Food", "a", minimum = .749f), paddle("Food", "b")).text)
        assertNull(choose(ml("Food"), paddle("Food", "a", minimum = null), paddle("Food", "b")).text)
        assertEquals("Food", choose(ml("Food"), paddle("Food", "a", .95f, .75f), paddle("Food", "b", .95f, .75f)).text)
    }

    @Test fun invalidScoresEmptyTextAndMissingRasterIdentityDoNotVote() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertNull(choose(ml("Food", invalid), tess("Food")).text)
            assertNull(choose(ml("Food"), paddle("Food", "a", minimum = invalid), paddle("Food", "b")).text)
        }
        assertNull(choose(ml("Food", id = " "), tess("Food")).text)
        assertNull(choose(ml("  "), tess("\t")).text)
        assertNull(OpticalCellConsensus.choose(emptyList()).text)
    }

    @Test fun weakAdditionalTokensAreRecordedButDoNotBlockOtherwiseSupportedText() {
        val weak = ml("1 Open Food", .4f)
        val result = choose(weak, tess("Open Food"), paddle("Open Food", "a"), paddle("Open Food", "b"))
        assertEquals("Open Food", result.text)
        assertEquals(listOf(weak), result.disagreements)
    }

    @Test fun individualMlScoresApplyExistingWordAndSymbolThresholdsWithoutChangingScores() {
        val observation = ml("- American Ch", .50f).copy(wordConfidences = listOf(
            WordConfidence("-", .50f), WordConfidence("American", .70f), WordConfidence("Ch", .65f)))
        val result = choose(observation, tess("- American Ch"))
        assertEquals("- American Ch", result.text)
        assertEquals(.50f, result.supportingObservations.first { it.family == Family.ML }.confidence)
        assertNull(choose(observation.copy(wordConfidences = emptyList()), tess("- American Ch")).text)
    }

    @Test fun perWordMlScoresCannotLowerTheThresholdForLettersNumbersOrSymbols() {
        fun observed(symbol: Float, word: Float, number: Float) = ml("- Food 1", .99f).copy(wordConfidences = listOf(
            WordConfidence("-", symbol), WordConfidence("Food", word), WordConfidence("1", number)))
        assertEquals("- Food 1", choose(observed(.50f, .65f, .65f), tess("- Food 1")).text)
        assertNull(choose(observed(.499f, .90f, .90f), tess("- Food 1")).text)
        assertNull(choose(observed(.90f, .649f, .90f), tess("- Food 1")).text)
        assertNull(choose(observed(.90f, .90f, .649f), tess("- Food 1")).text)
    }

    @Test fun perWordMlEvidenceMustDescribeEveryTokenAndHaveValidScores() {
        val base = ml("- Food 1", .70f)
        val invalidLists = listOf(
            listOf(WordConfidence("Food", .9f), WordConfidence("1", .9f)),
            listOf(WordConfidence("-", .9f), WordConfidence("Food 1 1", .9f)),
            listOf(WordConfidence("- Food 1", Float.NaN)),
            listOf(WordConfidence("- Food 1", 1.1f)),
        )
        for (words in invalidLists) {
            assertNull(choose(base.copy(wordConfidences = words), tess("- Food 1")).text)
        }
        val valid = base.copy(wordConfidences = listOf(WordConfidence("-", .70f), WordConfidence("Food", .80f), WordConfidence("1", .90f)))
        assertEquals(base.text, choose(valid, tess(base.text)).text)
    }
}

package com.facturaclara.app

import com.facturaclara.app.EngineVote.Candidate
import com.facturaclara.app.EngineVote.Family.ML
import com.facturaclara.app.EngineVote.Family.PADDLE
import com.facturaclara.app.EngineVote.Family.TESSERACT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineVoteTest {
    @Test fun twoOtherFamiliesOutvoteThePrimary() {
        assertEquals("730 Tavern", EngineVote.choose("790 Tavern",
            listOf(Candidate(PADDLE, "730 Tavern"), Candidate(TESSERACT, "730  Tavern"))))
    }

    @Test fun repeatedReadingsOfOneFamilyAreASingleVote() {
        assertEquals("790 Tavern", EngineVote.choose("790 Tavern",
            List(5) { Candidate(PADDLE, "730 Tavern") }))
    }

    @Test fun primaryWinsTies() {
        assertEquals("21.49", EngineVote.choose("21.49",
            listOf(Candidate(ML, "21.49"), Candidate(PADDLE, "21.49-"), Candidate(TESSERACT, "21.49-"), Candidate(PADDLE, "21.49"))))
    }

    @Test fun contrastPassAndPaddleAgreeingIsEnough() {
        assertEquals("CERDO", EngineVote.choose("CERD0", listOf(Candidate(ML, "CERDO"), Candidate(PADDLE, "CERDO"))))
    }

    @Test fun noAgreementKeepsThePrimaryVerbatim() {
        assertEquals("Pendl eton", EngineVote.choose("Pendl eton",
            listOf(Candidate(PADDLE, "Pendleton"), Candidate(TESSERACT, "Pendietan"), Candidate(ML, ""))))
    }

    @Test fun equallySupportedAlternativesKeepThePrimary() {
        assertEquals("750 Tavern", EngineVote.choose("750 Tavern", listOf(
            Candidate(PADDLE, "730 Tavern"), Candidate(PADDLE, "790 Tavern"),
            Candidate(TESSERACT, "790 Tavern"), Candidate(TESSERACT, "730 Tavern"))))
    }

    @Test fun paddleIsConsultedWhileAnotherReadingCouldStillWin() {
        assertTrue(EngineVote.paddleCanChange("CERD0", listOf(Candidate(ML, "CERDO"))))
        assertTrue(EngineVote.paddleCanChange("790 Tavern", listOf(Candidate(TESSERACT, "730 Tavern"))))
        // Tesseract backs the primary, but the contrast pass and the other Tesseract pass do not.
        assertTrue(EngineVote.paddleCanChange("21.49", listOf(Candidate(TESSERACT, "21.49"),
            Candidate(ML, "21.49-"), Candidate(TESSERACT, "21.49-"))))
    }

    @Test fun paddleIsSkippedWhenNoOtherFamilyDisagrees() {
        assertFalse(EngineVote.paddleCanChange("Total", emptyList()))
        assertFalse(EngineVote.paddleCanChange("Total 5.00", listOf(Candidate(ML, "Total  5.00"), Candidate(TESSERACT, "Total 5.00"))))
        // A lone dissent cannot beat the primary backed by Tesseract, even with Paddle's support.
        assertFalse(EngineVote.paddleCanChange("Total", listOf(Candidate(TESSERACT, "Total"), Candidate(ML, "Tota1"))))
    }

    @Test fun skippingPaddleNeverChangesTheChoice() {
        val texts = listOf(null, "a", "b", "a ", "")
        val paddleTexts = listOf("a", "b", "c", "")
        var skipped = 0
        for (primary in listOf("a", "b", " ", "a  ")) for (contrast in texts) for (first in texts) for (second in texts) {
            val others = listOfNotNull(contrast?.let { Candidate(ML, it) }, first?.let { Candidate(TESSERACT, it) },
                second?.let { Candidate(TESSERACT, it) })
            if (EngineVote.paddleCanChange(primary, others)) continue
            skipped++
            val withoutPaddle = EngineVote.choose(primary, others)
            for (mask in 0 until (1 shl paddleTexts.size)) {
                val paddle = paddleTexts.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.map { Candidate(PADDLE, it) }
                assertEquals("$primary $others $paddle", withoutPaddle, EngineVote.choose(primary, others + paddle))
            }
        }
        assertTrue(skipped > 0)
    }
}

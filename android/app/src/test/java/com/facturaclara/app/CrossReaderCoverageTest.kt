package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossReaderCoverageTest {
    private val label = OcrLine("Total", .10f, .50f, .20f, .52f)
    private val currency = OcrLine("S/", .21f, .50f, .30f, .52f)
    private val amount = OcrLine("118.00", .70f, .50f, .85f, .52f)
    private val cells = listOf(label, currency, amount)

    private fun unowned(words: List<OcrLine>, minimumConfidence: Float? = null) =
        CrossReaderVerifier.unownedWords(listOf(words), cells, 1000, 1000, minimumConfidence)

    @Test fun contextWordsWithoutExactlyOneOwnerCellAreReported() {
        val owned = OcrLine("118.00", .70f, .50f, .85f, .52f)
        // Equidistant from two adjacent cells: the assignment refuses to choose.
        val tie = OcrLine("1", .20f, .50f, .21f, .52f)
        // Visible only in a context strip, where the primary passes found no cell.
        val missingRow = OcrLine("Descuento", .10f, .80f, .30f, .82f)
        val betweenColumns = OcrLine("x", .45f, .50f, .48f, .52f)
        assertEquals(listOf(tie, missingRow, betweenColumns), unowned(listOf(owned, tie, missingRow, betweenColumns)))
        assertTrue(unowned(listOf(owned, OcrLine(" ", .45f, .80f, .48f, .82f))).isEmpty())
    }

    @Test fun tesseractContextKeepsItsConfidenceFloor() {
        val weak = OcrLine("Descuento", .10f, .80f, .30f, .82f, confidence = .50f)
        val strong = weak.copy(confidence = .90f)
        assertTrue(unowned(listOf(weak), .85f).isEmpty())
        assertEquals(listOf(strong), unowned(listOf(weak, strong), .85f))
    }

    private fun blocksAcceptance(tess: List<OcrLine>, ml: List<OcrLine> = emptyList()): Boolean {
        val coverage = CrossReaderVerifier.Coverage(false, false,
            uncoveredContextMl = unowned(ml).isNotEmpty(),
            uncoveredContextTess = CrossReaderVerifier.unownedTesseractContext(listOf(tess), cells, 1000, 1000).isNotEmpty(),
            contextMappingIncomplete = false)
        return coverage.unresolved(0) != 0
    }

    @Test fun tesseractEdgeSymbolWithoutOwnerNoLongerBlocks() {
        // 17_foto_girada: a «|» (confidence .92) on the paper's right edge was the only blocker.
        val edge = OcrLine("|", .97f, .78f, .98f, .84f, confidence = .92f)
        val blankPaper = OcrLine("=", .45f, .10f, .48f, .12f, confidence = .90f)
        assertEquals(listOf(edge, blankPaper), unowned(listOf(edge, blankPaper), .85f))
        assertTrue(CrossReaderVerifier.unownedTesseractContext(listOf(listOf(edge, blankPaper)), cells, 1000, 1000).isEmpty())
        assertFalse(blocksAcceptance(listOf(edge, blankPaper)))
    }

    @Test fun tesseractQuantityOrSignWithoutOwnerStillBlocks() {
        // 09_impresion_tenue: the quantity «1» of a row had no cell. A lone sign can belong to an amount.
        for (text in listOf("1", "-", "$", "€", "S/", "%", "/", "T1")) {
            val word = OcrLine(text, .10f, .80f, .12f, .82f, confidence = .90f)
            assertEquals(text, listOf(word), CrossReaderVerifier.unownedTesseractContext(listOf(listOf(word)), cells, 1000, 1000))
            assertTrue(text, blocksAcceptance(listOf(word)))
        }
    }

    @Test fun mlContextWithoutLettersOrDigitsStillBlocks() {
        val stars = OcrLine("****", .10f, .80f, .30f, .82f)
        assertEquals(listOf(stars), unowned(listOf(stars)))
        assertTrue(blocksAcceptance(emptyList(), ml = listOf(stars)))
        assertTrue(blocksAcceptance(emptyList(), ml = listOf(OcrLine("|", .97f, .78f, .98f, .84f))))
    }

    @Test fun tesseractSymbolFilterKeepsTheConfidenceFloor() {
        val weak = OcrLine("1", .10f, .80f, .12f, .82f, confidence = .50f)
        assertTrue(CrossReaderVerifier.unownedTesseractContext(listOf(listOf(weak)), cells, 1000, 1000).isEmpty())
    }

    @Test fun unownedContextTextPreventsAcceptance() {
        val clean = CrossReaderVerifier.Coverage(false, false, false, false, false)
        assertEquals(0, clean.unresolved(0))
        assertEquals(3, clean.unresolved(3))
        assertEquals(1, clean.copy(uncoveredContextMl = true).unresolved(0))
        assertEquals(1, clean.copy(uncoveredContextTess = true).unresolved(0))
        assertEquals(1, clean.copy(contextMappingIncomplete = true).unresolved(0))
        // One extra zone for all consulted text without an owner, never one per flag.
        assertEquals(3, CrossReaderVerifier.Coverage(true, true, true, true, true).unresolved(2))
    }

    @Test fun acceptanceAndFallbackAreGatedByTheSameEvidence() {
        for (mask in 0 until 32) {
            val coverage = CrossReaderVerifier.Coverage(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
                mask and 8 != 0, mask and 16 != 0)
            assertEquals("$coverage", coverage.blocked, coverage.unresolved(0) != 0)
        }
    }
}

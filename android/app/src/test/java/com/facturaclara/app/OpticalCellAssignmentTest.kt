package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpticalCellAssignmentTest {
    @Test fun mergedWordCannotConfirmDescriptionAndDuplicateItsQuantity() {
        val item = OcrLine("Item", .10f, .30f, .30f, .33f)
        val quantity = OcrLine("1", .305f, .30f, .315f, .33f)
        val merged = OcrLine("Item1", .10f, .30f, .315f, .33f)
        val separatelyObservedQuantity = OcrLine("1", .306f, .30f, .314f, .33f)
        val assigned = assign(listOf(merged, separatelyObservedQuantity), listOf(item, quantity))
        assertTrue(assigned[0].isEmpty())
        assertEquals(listOf(separatelyObservedQuantity), assigned[1])
        assertTrue(merged !in assigned.flatten())
    }

    @Test fun normalWordCanGrazeNeighborWithoutMateriallyCoveringIt() {
        val item = OcrLine("Item", .10f, .30f, .30f, .33f)
        val quantity = OcrLine("1", .305f, .30f, .315f, .33f)
        // Detector uncertainty extends 3px into a 10px neighboring cell.
        val word = OcrLine("Item", .25f, .30f, .308f, .33f)
        assertEquals(listOf(listOf(word), emptyList<OcrLine>()), assign(listOf(word), listOf(item, quantity)))
    }

    @Test fun mergedWordStillRejectsTwoCellsWithSmallBaselineDifferences() {
        val item = OcrLine("Item", .10f, .30f, .30f, .33f)
        val quantity = OcrLine("1", .305f, .303f, .315f, .333f)
        val merged = OcrLine("Item1", .10f, .30f, .315f, .33f)
        assertTrue(assign(listOf(merged), listOf(item, quantity)).all { it.isEmpty() })
    }

    @Test fun addressWordsAreNotRejectedForOverlapWithTheFollowingSlopingRow() {
        // Source geometry from cells.json and second17/verification-1.json.
        // The row below reaches upward, but its baseline is a worse fit.
        val address = OcrLine("FAIR 0AKS, CA", .39866668f, .341f, .56133336f, .359f)
        val below = OcrLine("1 Order is Tendered", .42933333f, .351f, .6613333f, .38f, -.028871464f)
        val primary = listOf(
            OcrLine("0AKS,", .46266666f, .341f, .52133334f, .359f),
            OcrLine("CA", .53866667f, .341f, .56133336f, .359f),
        )
        val independent = listOf(
            OcrLine("OAKS,", .462f, .341f, .52133334f, .3595f),
            OcrLine("CA", .53866667f, .341f, .56133336f, .3575f),
        )
        for (words in listOf(primary, independent)) {
            val assigned = OpticalCellAssignment.assign(words, listOf(address, below), 750, 1000)
            assertEquals(words, assigned[0])
            assertTrue(assigned[1].isEmpty())
        }
    }

    @Test fun tallWordRemainsInItsBestRowDespiteOverlapWithNextRowCell() {
        val own = OcrLine("1 Order is Tendered", .42933333f, .351f, .6613333f, .38f, -.028871464f)
        val next = OcrLine("PM", .584f, .368f, .612f, .393f, -.12499998f)
        val word = OcrLine("Tendered", .55733335f, .3475f, .6586667f, .3905f)
        val assigned = OpticalCellAssignment.assign(listOf(word), listOf(own, next), 750, 1000)
        assertEquals(listOf(word), assigned[0])
        assertTrue(assigned[1].isEmpty())
    }

    @Test fun clippedQuantityWithinExpandedHorizontalMarginRetainsAnOwner() {
        val description = OcrLine("Hashbrowns Side", .13466667f, .548f, .41466667f, .573f)
        val amount = OcrLine("2.99", .70f, .548f, .77f, .573f)
        val quantity = OcrLine("1", .112f, .548f, .120f, .573f)
        val assigned = OpticalCellAssignment.assign(listOf(quantity), listOf(description, amount), 750, 1000)
        assertEquals(listOf(quantity), assigned[0])
        assertTrue(assigned[1].isEmpty())
    }

    @Test fun equallyPlausibleMarginsLeaveWordUnassigned() {
        val left = OcrLine("left", .10f, .30f, .20f, .33f)
        val right = OcrLine("right", .22f, .30f, .32f, .33f)
        val between = OcrLine("1", .205f, .30f, .215f, .33f)
        assertTrue(assign(listOf(between), listOf(left, right)).all { it.isEmpty() })
    }

    @Test fun overlappingCellsCannotBothUseTheSameWord() {
        val first = OcrLine("first", .10f, .30f, .30f, .33f)
        val second = OcrLine("second", .15f, .30f, .35f, .33f)
        val word = OcrLine("shared", .18f, .30f, .25f, .33f)
        assertTrue(assign(listOf(word), listOf(first, second)).all { it.isEmpty() })
    }

    @Test fun tiltedAmountUsesProjectedBaselineInsteadOfNearestVerticalCenter() {
        val previous = OcrLine("previous row", .10f, .26f, .90f, .30f, -.08f)
        val own = OcrLine("own row", .10f, .30f, .90f, .34f, -.08f)
        // Its y-center .292 is closer to the previous cell's .280 than its
        // own cell's .320, but the sloping baseline projects to the latter.
        val amount = OcrLine("0.70", .80f, .282f, .90f, .302f, -.08f)
        assertEquals(listOf(emptyList<OcrLine>(), listOf(amount)), assign(listOf(amount), listOf(previous, own)))
    }

    private fun assign(words: List<OcrLine>, cells: List<OcrLine>) =
        OpticalCellAssignment.assign(words, cells, 1000, 1000)
}

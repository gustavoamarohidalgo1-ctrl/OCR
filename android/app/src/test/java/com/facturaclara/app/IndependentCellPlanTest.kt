package com.facturaclara.app

import org.junit.Assert.*
import org.junit.Test

class IndependentCellPlanTest {
    private val detected = box("detector unchanged", .55f, .30f, .90f)

    @Test fun disjointWordAddsBlankGeometryAndRetainsOriginalDetectorCell() {
        val word = box("1", .10f, .30f, .12f)
        val plan = plan(listOf(listOf(word)))
        assertEquals(detected, plan.cells[0].line)
        assertEquals(IndependentCellPlan.Source.INDEPENDENT_DETECTOR, plan.cells[0].source)
        assertEquals(0, plan.cells[0].detectorIndex)
        assertEquals(2, plan.cells.size)
        assertEquals(word.copy(text = "", confidence = null), plan.cells[1].line)
        assertEquals(IndependentCellPlan.Source.SUPPLEMENTARY_ML, plan.cells[1].source)
        assertNull(plan.cells[1].detectorIndex)
        assertEquals(listOf(IndependentCellPlan.WordOccurrence(0, 0, word)), plan.cells[1].occurrences)
        assertTrue(plan.uncovered.isEmpty())
    }

    @Test fun quantityOutsideDetectorPixelsGetsSupplementEvenWhenProximityAssignsIt() {
        val description = OcrLine("", .13f, .30f, .50f, .33f)
        val quantity = OcrLine("1", .115f, .302f, .125f, .328f, confidence = .55f)
        assertEquals(listOf(quantity), OpticalCellAssignment.assign(listOf(quantity), listOf(description), 1000, 1000)[0])
        val plan = IndependentCellPlan.build(listOf(description), listOf(listOf(quantity)), 1000, 1000)
        assertEquals(2, plan.cells.size)
        assertEquals(description, plan.cells[0].line)
        assertEquals(quantity.copy(text = "", confidence = null), plan.cells[1].line)
        assertEquals(IndependentCellPlan.Source.SUPPLEMENTARY_ML, plan.cells[1].source)
        assertTrue(plan.uncovered.isEmpty())
        val finalOwners = OpticalCellAssignment.assign(listOf(quantity), plan.cells.map { it.line }, 1000, 1000)
        assertTrue("The nearby description must relinquish the quantity", finalOwners[0].isEmpty())
        assertEquals(listOf(quantity), finalOwners[1])
    }

    @Test fun repeatedNearbyQuantitiesRemainSeparateOccurrencesAcrossRowsAndPasses() {
        val descriptions = listOf(OcrLine("", .13f, .30f, .50f, .33f), OcrLine("", .13f, .40f, .50f, .43f))
        val quantities = listOf(OcrLine("1", .115f, .302f, .125f, .328f, confidence = .55f),
            OcrLine("1", .115f, .402f, .125f, .428f, confidence = .55f))
        val secondPass = quantities.map { it.copy(text = "I", confidence = .1f) }
        val plan = IndependentCellPlan.build(descriptions, listOf(quantities, secondPass), 1000, 1000)
        assertEquals(4, plan.cells.size)
        assertEquals(descriptions, plan.cells.take(2).map { it.line })
        assertEquals(listOf(2, 2), plan.cells.drop(2).map { it.occurrences.size })
        assertTrue(plan.uncovered.isEmpty())
        for (reading in listOf(quantities, secondPass)) {
            val assignment = OpticalCellAssignment.assign(reading, plan.cells.map { it.line }, 1000, 1000)
            assertEquals(listOf(0, 0, 1, 1), assignment.map { it.size })
            assertEquals(reading, assignment.drop(2).flatten())
        }
    }

    @Test fun ambiguousDuplicateQuantitiesCannotDisappearIntoProximityOwner() {
        val description = OcrLine("", .13f, .30f, .50f, .33f)
        val quantity = OcrLine("1", .115f, .302f, .125f, .328f)
        val plan = IndependentCellPlan.build(listOf(description), listOf(listOf(quantity, quantity)), 1000, 1000)
        assertEquals(listOf(description), plan.cells.map { it.line })
        assertEquals(listOf(0, 1), plan.uncovered.map { it.wordIndex })
    }

    @Test fun samePhysicalWordAcrossPassesProducesOneEnvelopeWithoutTextVoting() {
        val first = box("1", .10f, .30f, .12f)
        val second = OcrLine("I", .098f, .298f, .122f, .332f, confidence = .10f)
        val plan = plan(listOf(listOf(first), listOf(second)))
        assertEquals(2, plan.cells.size)
        assertEquals(2, plan.cells[1].occurrences.size)
        assertEquals(OcrLine("", .098f, .298f, .122f, .332f), plan.cells[1].line)
        assertTrue(plan.uncovered.isEmpty())
    }

    @Test fun repeatedTextInDifferentRowsRetainsTwoPhysicalCells() {
        val a = box("1", .10f, .30f, .12f)
        val b = box("1", .10f, .40f, .12f)
        val plan = plan(listOf(listOf(a, b), listOf(a.copy(text = "I"), b)))
        assertEquals(3, plan.cells.size)
        assertEquals(listOf(.30f, .40f), plan.cells.drop(1).map { it.line.top })
        assertEquals(listOf(2, 2), plan.cells.drop(1).map { it.occurrences.size })
    }

    @Test fun equalTextAtSeparatePositionsInSameRowDoesNotDeduplicate() {
        val left = box("1", .10f, .30f, .12f)
        val right = box("1", .25f, .30f, .27f)
        val plan = plan(listOf(listOf(left, right)))
        assertEquals(3, plan.cells.size)
        assertEquals(listOf(.10f, .25f), plan.cells.drop(1).map { it.line.left })
    }

    @Test fun partiallyOverlappingUnassignedWordDoesNotExpandDetectedCell() {
        val partial = box("long merged text", .43f, .30f, .56f)
        val plan = plan(listOf(listOf(partial)))
        assertEquals(listOf(detected), plan.cells.map { it.line })
        assertEquals(listOf(IndependentCellPlan.WordOccurrence(0, 0, partial)), plan.uncovered)
    }

    @Test fun wordAlreadyOwnedByDetectorGetsNoSupplement() {
        val word = box("Item", .60f, .30f, .80f)
        val plan = plan(listOf(listOf(word)))
        assertEquals(listOf(detected), plan.cells.map { it.line })
        assertTrue(plan.uncovered.isEmpty())
    }

    @Test fun overlappingWordsFromSamePassAreNotCollapsedIntoOnePhysicalOccurrence() {
        val first = box("1", .10f, .30f, .12f)
        val plan = plan(listOf(listOf(first, first)))
        assertEquals(1, plan.cells.size)
        assertEquals(listOf(0, 1), plan.uncovered.map { it.wordIndex })
    }

    @Test fun mergedCrossPassWordCannotJoinTwoSeparatedPhysicalWords() {
        val left = box("1", .10f, .30f, .12f)
        val right = box("2", .14f, .30f, .16f)
        val merged = box("12", .10f, .30f, .16f)
        val plan = plan(listOf(listOf(left, right), listOf(merged)))
        assertEquals(1, plan.cells.size)
        assertEquals(3, plan.uncovered.size)
    }

    @Test fun overlapChainDoesNotGrowIntoAnArtificialLine() {
        val a = box("a", .10f, .30f, .14f)
        val b = box("b", .12f, .30f, .16f)
        val c = box("c", .14f, .30f, .18f)
        val plan = plan(listOf(listOf(a), listOf(b), listOf(c)))
        assertEquals(1, plan.cells.size)
        assertEquals(3, plan.uncovered.size)
    }

    @Test fun tallOverlappingWordsOnDistinctBaselinesRemainUnresolved() {
        val a = OcrLine("1", .10f, .30f, .12f, .35f)
        val b = OcrLine("1", .10f, .34f, .12f, .39f)
        val plan = plan(listOf(listOf(a), listOf(b)))
        assertEquals(1, plan.cells.size)
        assertEquals(2, plan.uncovered.size)
    }

    @Test fun noIndependentDetectionDoesNotRevertToMlOnlyPageGeometry() {
        val word = box("1", .10f, .30f, .12f)
        val plan = IndependentCellPlan.build(emptyList(), listOf(listOf(word)), 1000, 1000)
        assertTrue(plan.cells.isEmpty())
        assertEquals(listOf(IndependentCellPlan.WordOccurrence(0, 0, word)), plan.uncovered)
    }

    @Test fun supplementaryGeometryDoesNotItselfConfirmItsText() {
        val word = box("1", .10f, .30f, .12f)
        val plan = plan(listOf(listOf(word)))
        val geometry = plan.cells.map { it.line }
        val consensus = IndependentPageConsensus.evaluate(geometry, List(geometry.size) { emptyList() },
            listOf(listOf(word)), emptyList(), 1000, 1000)
        assertTrue(consensus.unresolved > 0)
        assertTrue(consensus.decisions.all { it.text == null })
    }

    private fun plan(readings: List<List<OcrLine>>) = IndependentCellPlan.build(listOf(detected), readings, 1000, 1000)
    private fun box(text: String, left: Float, top: Float, right: Float) =
        OcrLine(text, left, top, right, top + .03f, confidence = .95f)
}

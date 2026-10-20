package com.facturaclara.app

import com.facturaclara.app.OpticalCellConsensus.Family
import com.facturaclara.app.OpticalCellConsensus.Observation
import org.junit.Assert.*
import org.junit.Test

class IndependentPageConsensusTest {
    @Test fun independentCellIncludesQuantityOutsidePrimaryDescriptionBox() {
        val cell = box("", .08f, .30f, .50f)
        val words = listOf(box("1", .08f, .30f, .10f), box("Hashbrowns", .14f, .30f, .35f),
            box("Side", .37f, .30f, .50f))
        val result = evaluate(listOf(cell), listOf(paddle("1 Hashbrowns Side")), listOf(words))
        assertEquals(0, result.unresolved)
        assertEquals("1 Hashbrowns Side", result.lines.single().text)
        assertEquals(.08f, result.lines.single().left, 0f)
        assertEquals(words, result.assignedMl.single().single())
    }

    @Test fun repeatedItemsCannotBorrowQuantityEvidenceFromAnotherRow() {
        val cells = listOf(box("", .08f, .30f, .50f), box("", .08f, .40f, .50f))
        val first = listOf(box("1", .08f, .30f, .10f), box("Hashbrowns Side", .14f, .30f, .50f))
        val second = listOf(box("Hashbrowns Side", .14f, .40f, .50f))
        val result = evaluate(cells, List(2) { paddle("1 Hashbrowns Side", "row-$it") }, listOf(first + second))
        assertEquals("1 Hashbrowns Side", result.decisions[0].text)
        assertNull(result.decisions[1].text)
        assertEquals(1, result.unresolved)
        assertEquals(first, result.assignedMl.single()[0])
        assertEquals(second, result.assignedMl.single()[1])
    }

    @Test fun weakPrimaryQuantityCannotBeDeletedByTwoOtherReaders() {
        val cell = box("", .08f, .30f, .50f)
        val words = listOf(box("1", .08f, .30f, .10f, .10f), box("Open Food", .14f, .30f, .50f))
        val result = evaluate(listOf(cell), listOf(paddle("Open Food")), listOf(words),
            listOf(listOf(box("Open Food", .14f, .30f, .50f))))
        assertNull(result.decisions.single().text)
        assertTrue("omitted_primary_tokens" in result.decisions.single().blockers)
    }

    @Test fun weakQuantitySeenOnlyInRectifiedMlCropCannotBeDeleted() {
        val cell = box("", .08f, .30f, .50f)
        val result = evaluate(listOf(cell), listOf(paddle("Item")),
            listOf(listOf(box("Item", .14f, .30f, .50f))),
            listOf(listOf(box("Item", .14f, .30f, .50f))), extra = listOf(listOf(
                Observation("1 Item", .10f, "rectified-ml-0", Family.ML,
                    wordConfidences = listOf(OpticalCellConsensus.WordConfidence("1", .10f),
                        OpticalCellConsensus.WordConfidence("Item", .95f))),
            )))
        assertNull(result.decisions.single().text)
        assertTrue("omitted_context_tokens" in result.decisions.single().blockers)
    }

    @Test fun weakRectifiedNumericGroupingConflictIsNotNormalizedAway() {
        val cell = box("", .10f, .30f, .80f)
        val result = evaluate(listOf(cell), listOf(paddle("2/60.00")),
            listOf(listOf(box("2/60.00", .10f, .30f, .80f))), extra = listOf(listOf(
                Observation("2 / 60.00", .10f, "rectified-ml-0", Family.ML),
            )))
        assertNull(result.decisions.single().text)
        assertTrue("conflicting_numeric_grouping" in result.decisions.single().blockers)
    }

    @Test fun weakWholeNumericGroupingConflictIsNotNormalizedAway() {
        val cell = box("", .10f, .30f, .80f)
        val result = evaluate(listOf(cell), listOf(paddle("2/60.00")),
            listOf(listOf(box("2 / 60.00", .10f, .30f, .80f, .10f))),
            listOf(listOf(box("2/60.00", .10f, .30f, .80f))))
        assertNull(result.decisions.single().text)
        assertTrue("conflicting_numeric_grouping" in result.decisions.single().blockers)
    }

    @Test fun DifferentWeakContextStringDoesNotVetoAnOtherwiseLiteralAgreement() {
        val cell = box("", .10f, .30f, .50f)
        val result = evaluate(listOf(cell), listOf(paddle("Item")),
            listOf(listOf(box("Item", .10f, .30f, .50f))), extra = listOf(listOf(
                Observation("ltern", .10f, "rectified-ml-0", Family.ML),
            )))
        assertEquals("Item", result.decisions.single().text)
        assertEquals(0, result.unresolved)
    }

    @Test fun repeatedEqualOccurrencesAreNotCollapsedByValueEquality() {
        val cell = box("", .10f, .30f, .30f)
        val repeated = box("1", .12f, .30f, .14f)
        val result = evaluate(listOf(cell), listOf(paddle("1 1")), listOf(listOf(repeated, repeated)))
        assertEquals(0, result.unresolved)
        assertEquals("1 1", result.lines.single().text)
        assertEquals(2, result.assignedMl.single().single().size)
    }

    @Test fun anyWholeMlWordOutsideDetectorCellsBlocksCompletePageIncludingWeakWords() {
        val cell = box("", .10f, .30f, .30f)
        val orphan = box("1", .60f, .70f, .62f, .05f)
        val result = evaluate(listOf(cell), listOf(paddle("Item")),
            listOf(listOf(box("Item", .10f, .30f, .30f), orphan, orphan)))
        assertEquals("Item", result.decisions.single().text)
        assertEquals(1, result.unresolved)
        assertEquals(listOf(1, 2), result.uncoveredMl.map { it.wordIndex })
        assertEquals(listOf(0, 0), result.uncoveredMl.map { it.readingIndex })
    }

    @Test fun reliableTessOrphanIsReportedWithItsOwnOccurrence() {
        val cell = box("", .10f, .30f, .30f)
        val orphan = box("1", .60f, .70f, .62f, .91f)
        val result = evaluate(listOf(cell), listOf(paddle("Item")),
            listOf(listOf(box("Item", .10f, .30f, .30f))), listOf(listOf(orphan)))
        assertEquals(1, result.unresolved)
        assertEquals(listOf(IndependentPageConsensus.WordOccurrence(0, 0, orphan)), result.uncoveredTess)
    }

    @Test fun weakTessOrphanIsNotClaimedAsReliableCoverageEvidence() {
        val cell = box("", .10f, .30f, .30f)
        val result = evaluate(listOf(cell), listOf(paddle("Item")),
            listOf(listOf(box("Item", .10f, .30f, .30f))),
            listOf(listOf(box("noise", .60f, .70f, .80f, .84f))))
        assertEquals(0, result.unresolved)
        assertTrue(result.uncoveredTess.isEmpty())
    }

    @Test fun contextualReadingCanCorroborateFullCellWithoutInventingWordPositions() {
        val cell = box("", .08f, .30f, .80f)
        val result = evaluate(listOf(cell), listOf(paddle("1 Item 2.99")),
            listOf(listOf(box("Item", .20f, .30f, .50f))), extra = listOf(listOf(
                Observation("1 Item 2.99", .93f, "rectified-ml-cell-0", Family.ML),
            )))
        assertEquals(0, result.unresolved)
        assertEquals(1, result.lines.size)
        assertEquals(cell.copy(text = "1 Item 2.99"), result.lines.single())
    }

    @Test fun rereadingTheSameFamilyCannotResolveAFullPageCell() {
        val cell = box("", .10f, .30f, .30f)
        val result = evaluate(listOf(cell), listOf(emptyList()),
            List(3) { listOf(box("Item", .10f, .30f, .30f)) }, extra = listOf(listOf(
                Observation("Item", .99f, "ml-other-raster", Family.ML),
            )))
        assertNull(result.decisions.single().text)
    }

    @Test fun duplicatePaddleRasterDoesNotBecomeTwoSupportingObservations() {
        val cell = box("", .10f, .30f, .30f)
        val one = paddle("Item").first()
        val result = evaluate(listOf(cell), listOf(listOf(one, one)),
            listOf(listOf(box("Item", .10f, .30f, .30f))))
        assertNull(result.decisions.single().text)
    }

    @Test fun literalNumericGroupingConflictRemainsUnresolved() {
        val cell = box("", .10f, .30f, .80f)
        val result = evaluate(listOf(cell), listOf(paddle("2/60.00")),
            listOf(listOf(box("2 / 60.00", .10f, .30f, .80f))),
            listOf(listOf(box("2/60.00", .10f, .30f, .80f))))
        assertNull(result.decisions.single().text)
        assertTrue("conflicting_numeric_grouping" in result.decisions.single().blockers)
    }

    @Test fun mergedWordCannotPopulateTwoDetectedCellsOrLoseTheQuantity() {
        val cells = listOf(box("", .10f, .30f, .30f), box("", .305f, .30f, .315f))
        val merged = box("Item1", .10f, .30f, .315f)
        val result = evaluate(cells, listOf(paddle("Item"), paddle("1", "quantity")),
            listOf(listOf(merged)), listOf(listOf(box("Item", .10f, .30f, .30f), box("1", .305f, .30f, .315f))))
        assertEquals(listOf(IndependentPageConsensus.WordOccurrence(0, 0, merged)), result.uncoveredMl)
        assertTrue(result.assignedMl.single().all { it.isEmpty() })
        assertTrue(result.unresolved > 0)
    }

    @Test fun ambiguousWordBetweenTwoCellsHasNoInventedOwner() {
        val cells = listOf(box("", .10f, .30f, .20f), box("", .22f, .30f, .32f))
        val between = box("1", .205f, .30f, .215f)
        val result = evaluate(cells, listOf(paddle("1"), paddle("1", "right")), listOf(listOf(between)))
        assertEquals(listOf(IndependentPageConsensus.WordOccurrence(0, 0, between)), result.uncoveredMl)
        assertTrue(result.decisions.all { it.text == null })
    }

    @Test fun oneIndivisibleCellRetainsEveryCharacterAndItsIndependentGeometry() {
        val cell = OcrLine("", .10f, .30f, .90f, .36f, -.03f, .98f)
        val text = "2 Coffee 2.95 5.90"
        val result = evaluate(listOf(cell), listOf(paddle(text)), listOf(listOf(cell.copy(text = text))))
        assertEquals(0, result.unresolved)
        assertEquals(listOf(cell.copy(text = text)), result.lines)
    }

    @Test fun emptyDetectionCannotDeclareAnEmptyPageConfirmed() {
        assertEquals(1, evaluate(emptyList(), emptyList(), emptyList()).unresolved)
    }

    @Test(expected = IllegalArgumentException::class)
    fun paddleSlotCannotLabelAnotherReaderAsPaddleEvidence() {
        evaluate(listOf(box("", .10f, .30f, .30f)),
            listOf(listOf(Observation("Item", .99f, "wrong-family", Family.ML))), emptyList())
    }

    private fun evaluate(
        cells: List<OcrLine>, paddle: List<List<Observation>>, ml: List<List<OcrLine>>,
        tess: List<List<OcrLine>> = emptyList(), extra: List<List<Observation>> = List(cells.size) { emptyList() },
    ) = IndependentPageConsensus.evaluate(cells, paddle, ml, tess, 1000, 1000, extra)

    private fun box(text: String, left: Float, top: Float, right: Float, confidence: Float = .95f) =
        OcrLine(text, left, top, right, top + .03f, confidence = confidence)

    private fun paddle(text: String, prefix: String = "cell") = listOf("a", "b").map {
        Observation(text, .99f, "$prefix-raster-$it", Family.PADDLE, .97f)
    }
}

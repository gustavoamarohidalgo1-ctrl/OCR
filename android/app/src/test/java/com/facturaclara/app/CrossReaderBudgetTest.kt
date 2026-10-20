package com.facturaclara.app

import org.junit.Assert.*
import org.junit.Test

class CrossReaderBudgetTest {
    private val cell = OcrLine("observed", .1f, .1f, .2f, .2f)
    private fun row(count: Int) = RegionalOcrVerifier.RowEvidence(
        listOf(List(count) { cell }, List(count) { cell }, List(count) { cell }),
        emptyList(), emptyList(), true)
    private fun result(vararg rows: RegionalOcrVerifier.RowEvidence) =
        RegionalOcrVerifier.Result(emptyList(), 1, 0, rows.toList())

    @Test fun completeDocumentMustFitBudgetWithoutTruncation() {
        assertTrue(CrossReaderVerifier.canAttempt(result(row(32), row(32))))
        assertFalse(CrossReaderVerifier.canAttempt(result(row(32), row(33))))
        assertFalse(CrossReaderVerifier.canAttempt(result(row(123))))
    }

    @Test fun missingWholeCoverageCannotUseAdditionalReaders() {
        assertFalse(CrossReaderVerifier.canAttempt(result()))
        assertFalse(CrossReaderVerifier.canAttempt(result(row(0))))
        assertFalse(CrossReaderVerifier.canAttempt(result(row(27)).copy(unassignedWholeText = true)))
    }

    @Test fun eachCellCountsOnceUsingFirstAvailableWholeObservation() {
        val recoveredRow = row(64).copy(whole = listOf(emptyList(), List(64) { cell }, List(64) { cell }))
        assertTrue(CrossReaderVerifier.canAttempt(result(recoveredRow)))
    }
}

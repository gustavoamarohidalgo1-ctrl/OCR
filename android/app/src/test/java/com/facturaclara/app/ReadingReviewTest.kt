package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingReviewTest {
    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float, confidence: Float? = .95f) =
        OcrLine(text, left, top, right, bottom, confidence = confidence)

    @Test fun agreeingFragmentIsNotMarked() {
        val primary = listOf(line("TOTAL 118.00", .1f, .5f, .6f, .55f))
        val marked = ReadingReview.mark(primary, listOf(line("TOTAL 118.00", .11f, .501f, .61f, .552f)))
        assertFalse(marked.single().doubtful)
    }

    @Test fun differentDigitsAreMarked() {
        val primary = listOf(line("TOTAL 118.00", .1f, .5f, .6f, .55f))
        val marked = ReadingReview.mark(primary, listOf(line("TOTAL 116.00", .1f, .5f, .6f, .55f)))
        assertTrue(marked.single().doubtful)
    }

    @Test fun differentFragmentationStillCountsAsSupport() {
        val primary = listOf(line("TOTAL 118.00", .1f, .5f, .6f, .55f))
        val second = listOf(line("TOTAL", .1f, .5f, .3f, .55f), line("118.00", .45f, .5f, .6f, .55f))
        assertFalse(ReadingReview.mark(primary, second).single().doubtful)
    }

    @Test fun sameTextOnAnotherRowIsNotSupport() {
        val primary = listOf(line("1.50", .7f, .2f, .8f, .25f))
        val marked = ReadingReview.mark(primary, listOf(line("1.50", .7f, .6f, .8f, .65f)))
        assertTrue(marked.single().doubtful)
    }

    @Test fun repeatedTokenNeedsRepeatedSupport() {
        val primary = listOf(line("2 2", .1f, .2f, .3f, .25f))
        val marked = ReadingReview.mark(primary, listOf(line("2", .1f, .2f, .3f, .25f)))
        assertTrue(marked.single().doubtful)
    }

    @Test fun lowConfidenceIsMarkedEvenWhenReadingsAgree() {
        val primary = listOf(line("Café", .1f, .2f, .3f, .25f, confidence = .40f))
        assertTrue(ReadingReview.mark(primary, primary).single().doubtful)
    }

    @Test fun blankSecondReadingMarksEverythingAndKeepsTextVerbatim() {
        val primary = listOf(line("RUC 20100070970", .1f, .1f, .5f, .15f), line("IGV  18.00", .1f, .3f, .5f, .35f))
        val marked = ReadingReview.mark(primary, emptyList())
        assertTrue(marked.all { it.doubtful })
        assertEquals(primary.map { it.text }, marked.map { it.text })
        assertEquals(primary.map { it.copy(doubtful = true) }, marked)
    }

    @Test fun oneSecondWordCannotSupportTwoColumns() {
        // Primary read the amount 12.50 as 12.00; the second reading merged the row.
        val price = line("12.00", .60f, .40f, .70f, .44f)
        val amount = line("12.00", .80f, .40f, .90f, .44f)
        val secondRow = line("Arroz 1 12.00 12.50", .20f, .40f, .90f, .44f)
        val secondWords = listOf(line("Arroz", .20f, .40f, .30f, .44f), line("1", .40f, .40f, .42f, .44f),
            line("12.00", .60f, .40f, .70f, .44f), line("12.50", .80f, .40f, .90f, .44f))
        val marked = ReadingReview.mark(listOf(price, amount), listOf(secondRow), listOf(price, amount), secondWords)
        assertEquals(listOf(false, true), marked.map { it.doubtful })
    }

    @Test fun wordLevelSupportAcceptsDifferentFragmentation() {
        val primary = line("TOTAL 118.00", .1f, .5f, .6f, .55f)
        val primaryWords = listOf(line("TOTAL", .1f, .5f, .3f, .55f), line("118.00", .45f, .5f, .6f, .55f))
        val secondWords = listOf(line("TOTAL", .11f, .5f, .31f, .55f), line("118.00", .46f, .5f, .61f, .55f))
        assertFalse(ReadingReview.mark(listOf(primary), emptyList(), primaryWords, secondWords).single().doubtful)
    }

    @Test fun pageWithoutGeometryIsFlaggedWhenUnconfirmed() {
        val page = ScanPage(0, "", "TOTAL  118.00", blockers = listOf("Las dos lecturas no coincidieron."))
        assertTrue(ReceiptStructure.rows(page).flatMap { it.cells }.all { it.doubtful })
        assertTrue(ReceiptStructure.rows(page.copy(blockers = emptyList())).flatMap { it.cells }.none { it.doubtful })
    }

    @Test fun doubtfulFlagReachesTheReceiptCells() {
        val lines = listOf(line("Arroz", .1f, .3f, .3f, .34f), line("4.50", .7f, .3f, .8f, .34f).copy(doubtful = true))
        val page = ScanPage(0, "", OcrLayout.text(lines), lines = lines)
        val cells = ReceiptStructure.rows(page).flatMap { it.cells }
        assertEquals(listOf(false, true), cells.map { it.doubtful })
    }

    @Test fun impossibleSeparatorsAreFlaggedEvenWhenReadingsAgree() {
        val total = line("Total Bruto 51,431.963.91", .5f, .6f, .9f, .62f)
        val marked = ReadingReview.mark(listOf(total), listOf(total))
        assertTrue(marked.single().doubtful)
        assertEquals("Total Bruto 51,431.963.91", marked.single().text)
    }

    @Test fun onlyImpossibleAmountsCount() {
        listOf("51,431.963.91", "1434.275.66", "12.345.678.90", "S/.1,234.567.80", "1.434,275.66").forEach {
            assertTrue(it, ReadingReview.impossibleAmount("Total $it"))
        }
        listOf("1,434,275.66", "16.806,72", "1.234.567,89", "32,261,649.21", "118.00", "672.269", "12.345.678-5",
            "29.01.2019", "12.05.19", "2016-05-27T11:38:01", "S/.1,234.50", "IVA 21.0%", "1,234.5").forEach {
            assertFalse(it, ReadingReview.impossibleAmount("Total $it"))
        }
    }
}

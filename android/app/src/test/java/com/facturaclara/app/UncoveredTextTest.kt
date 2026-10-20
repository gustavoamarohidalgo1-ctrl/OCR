package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UncoveredTextTest {
    private fun box(left: Float, top: Float, right: Float, bottom: Float, text: String = "") = OcrLine(text, left, top, right, bottom)
    private fun reading(text: String, confidence: Float, raster: String) = UncoveredText.Reading(text, confidence, raster)

    @Test fun onlyBoxesNoLineCoversAreOpen() {
        val lines = listOf(box(.2f, .46f, .3f, .5f, "TOTAL:"), box(.2f, .51f, .7f, .54f, "Cash Tendered: 5.35"))
        val total = box(.63f, .46f, .71f, .5f)          // the bold amount ML Kit missed
        val tendered = box(.65f, .51f, .70f, .54f)      // already inside a read line
        val shifted = box(.18f, .455f, .31f, .505f)     // the same label seen a little larger
        assertEquals(listOf(0), UncoveredText.uncovered(listOf(total, tendered, shifted), lines))
    }

    @Test fun twoRastersMustAgreeAndOneMustBeConfident() {
        assertEquals("$5.35", UncoveredText.agreed(listOf(reading("$5.35", .98f, "a"), reading("$5.35", .9f, "b"),
            reading("5.35", .97f, "c")))?.text)
        // The same raster read twice is one observation.
        assertNull(UncoveredText.agreed(listOf(reading("$0.00", .99f, "a"), reading("$0.00", .99f, "a"))))
        // Agreement without confidence is noise (handwriting, cut-off text).
        assertNull(UncoveredText.agreed(listOf(reading("why do I aleays got", .72f, "a"), reading("why do I aleays got", .7f, "b"))))
        // Signs only are not text, and one or two characters are a logo mark or a stroke.
        assertNull(UncoveredText.agreed(listOf(reading("--", .99f, "a"), reading("--", .99f, "b"))))
        assertNull(UncoveredText.agreed(listOf(reading("SH", .9f, "a"), reading("SH", .95f, "b"))))
        assertEquals("19950", UncoveredText.agreed(listOf(reading("19950", .93f, "a"), reading("19950", .9f, "b")))?.text)
    }

    @Test fun equallySupportedTextsDoNotDecide() {
        assertNull(UncoveredText.agreed(listOf(reading("10N", .95f, "a"), reading("10N", .95f, "b"),
            reading("U0N", .95f, "c"), reading("U0N", .95f, "d"))))
    }

    @Test fun whitespaceDoesNotSplitSupport() {
        assertEquals("L1 T1", UncoveredText.agreed(listOf(reading("L1  T1", .95f, "a"), reading("L1 T1", .91f, "b")))?.text)
    }

    @Test fun textAlreadyOnTheRowIsNotAddedAgain() {
        val lines = listOf(box(.25f, .53f, .31f, .56f, "Total"), box(.445f, .53f, .48f, .56f, "7.61"))
        assertTrue(UncoveredText.alreadyRead("7.61", box(.43f, .535f, .49f, .565f), lines))
        assertFalse(UncoveredText.alreadyRead("$5.35", box(.63f, .46f, .71f, .5f), lines))
    }

    @Test fun recoveredTextIsAlwaysDoubtful() {
        val line = UncoveredText.recovered(box(.63f, .46f, .71f, .5f), reading("$5.35", .98f, "a"))
        assertTrue(line.doubtful)
        assertEquals("$5.35", line.text)
        assertEquals(.63f, line.left)
    }

    @Test fun aFigureInsideAnotherNumberOrColumnIsStillMissing() {
        val row = listOf(box(.1f, .5f, .3f, .53f, "Subtotal 15.35"), box(.35f, .5f, .45f, .53f, "$5.35"))
        // «5.35» only appears inside «15.35»: not read.
        assertFalse(UncoveredText.alreadyRead("5.35", box(.8f, .5f, .9f, .53f), listOf(row[0])))
        // The same amount in a far column of the row is a different figure.
        assertFalse(UncoveredText.alreadyRead("$5.35", box(.8f, .5f, .9f, .53f), row))
        // Right beside the box it is the same text seen twice.
        assertTrue(UncoveredText.alreadyRead("$5.35", box(.44f, .5f, .52f, .53f), row))
    }

    @Test fun removedDecorativeRowsCountAsCovered() {
        val stars = box(.1f, .2f, .9f, .22f, "XXXXXXXXXXXX")
        assertTrue(UncoveredText.uncovered(listOf(box(.12f, .2f, .88f, .22f)), listOf(stars)).isEmpty())
    }

    @Test fun blocksAndPicturesAreNotLines() {
        assertFalse(UncoveredText.lineSized(box(.1f, .1f, .9f, .5f)))
        assertTrue(UncoveredText.lineSized(box(.6f, .46f, .72f, .5f)))
    }
}

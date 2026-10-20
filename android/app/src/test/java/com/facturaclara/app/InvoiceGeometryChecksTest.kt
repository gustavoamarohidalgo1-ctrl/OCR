package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceGeometryChecksTest {
    private fun line(text: String, left: Float, top: Float, right: Float) =
        OcrLine(text, left, top, right, top + .02f)

    private fun header() = listOf(
        line("Cant.", .10f, .20f, .18f),
        line("Descripción", .25f, .20f, .55f),
        line("Importe", .80f, .20f, .95f),
    )

    private fun inspect(lines: List<OcrLine>) = InvoiceGeometryChecks.inspect(OcrLayout.text(lines), lines)

    @Test fun orphanAmountRowInRecognizedTableIsBlockedWithoutReassociation() {
        val lines = header() + listOf(
            line("40.00", .80f, .30f, .95f),
            line("2", .10f, .40f, .14f),
            line("Archivador", .25f, .40f, .55f),
        )
        val original = lines.toList()
        assertEquals(1, inspect(lines).size)
        assertEquals(original, lines)
    }

    @Test fun separateCurrencySymbolDoesNotHideAnOrphanAmount() {
        val lines = header() + listOf(
            line("S/", .70f, .30f, .76f),
            line("40.00", .80f, .30f, .95f),
        )
        assertEquals(1, inspect(lines).size)
    }

    @Test fun attachedCurrencyAndDecimalWithoutLeadingZeroRemainOrphanAmounts() {
        for (value in listOf("€40.00", "£40.00", "40.00€", "-€40.00", ".17", ",17", "$.17", "₹40.00")) {
            assertEquals(value, 1, inspect(header() + line(value, .80f, .30f, .95f)).size)
        }
    }

    @Test fun itemDescriptionContainingLettersAndDigitsIsAllowed() {
        val lines = header() + listOf(
            line("2", .10f, .30f, .14f),
            line("Cartucho 123A", .25f, .30f, .55f),
            line("40.00", .80f, .30f, .95f),
        )
        assertTrue(inspect(lines).isEmpty())
    }

    @Test fun summaryAndNumericFooterOutsideTableAreAllowed() {
        val lines = header() + listOf(
            line("Archivador", .25f, .30f, .55f),
            line("40.00", .80f, .30f, .95f),
            line("TOTAL", .60f, .40f, .75f),
            line("40.00", .80f, .40f, .95f),
            line("123456", .25f, .50f, .55f),
        )
        assertTrue(inspect(lines).isEmpty())
    }

    @Test fun ticketWithoutExplicitTableHeaderDoesNotUseThisRule() {
        val lines = listOf(
            line("COMERCIO", .25f, .10f, .55f),
            line("Archivador", .25f, .20f, .55f),
            line("40.00", .80f, .30f, .95f),
        )
        assertTrue(inspect(lines).isEmpty())
    }

    @Test fun emptyRowInsideTableDoesNotCountAsAnOrphanAmount() {
        assertTrue(InvoiceGeometryChecks.inspect(
            "Cant.  Descripción  Importe\n\n2  Cartucho 123A  40.00", emptyList()
        ).isEmpty())
    }
}

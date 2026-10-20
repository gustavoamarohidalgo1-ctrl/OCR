package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {
    private fun row(y: Float, vararg cells: Pair<String, Float>) = cells.map { (text, left) ->
        OcrLine(text, left, y, left + text.length * .0062f, y + .012f)
    }

    @Test fun wideInvoiceIsASheet() {
        // About 0.0062 of the page width per character: some 150 characters across.
        val lines = (0 until 6).flatMap { i -> row(.1f + i * .03f, "Descripción del servicio $i" to .05f,
            "1.00" to .55f, "1,850.00" to .7f, "IVA 16 %" to .82f, "2,210.75" to .9f) }
        assertTrue(SheetLayout.lineCapacity(lines) > 90f)
        assertTrue(SheetLayout.isSheet(lines))
    }

    @Test fun narrowReceiptIsNotASheet() {
        // About 0.022 per character: some 40 characters across the ticket.
        val lines = (0 until 10).map { i -> OcrLine("Producto $i", .1f, .1f + i * .05f, .1f + 10 * .022f, .13f + i * .05f) } +
            (0 until 10).map { i -> OcrLine("12.50", .78f, .1f + i * .05f, .78f + 5 * .022f, .13f + i * .05f) }
        assertFalse(SheetLayout.isSheet(lines))
    }

    @Test fun strayMarkBesideAReceiptDoesNotMakeItASheet() {
        // 66 characters across half of the photo, plus a mark on the table far to the right.
        val text = "Producto de limpieza multiusos con aroma a lavanda 1 L      12.50"
        val receipt = (0 until 10).map { i -> OcrLine(text, .2f, .1f + i * .04f, .7f, .125f + i * .04f) }
        val stray = OcrLine("|||", .92f, .5f, .93f, .52f)
        assertTrue(SheetLayout.lineCapacity(receipt + stray) < 70f)
        assertFalse(SheetLayout.isSheet(receipt + stray))
    }

    @Test fun fewFragmentsAreNeverASheet() {
        assertFalse(SheetLayout.isSheet(row(.1f, "Una línea muy larga de una factura A4 con muchos caracteres" to .02f)))
    }

    @Test fun fitKeepsTheSourceAspectInsideTheArea() {
        val area = SheetLayout.Box(36f, 48f, 523f, 700f)
        val portrait = SheetLayout.fit(595f / 842f, area)
        assertEquals(700f, portrait.height, .01f)
        assertEquals(700f * 595f / 842f, portrait.width, .01f)
        assertEquals(36f + (523f - portrait.width) / 2f, portrait.left, .01f)
        val landscape = SheetLayout.fit(1.5f, area)
        assertEquals(523f, landscape.width, .01f)
        assertEquals(523f / 1.5f, landscape.height, .01f)
    }
}

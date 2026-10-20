package com.facturaclara.app

import org.junit.Assert.*
import org.junit.Test

class ReceiptStructureTest {
    private fun line(text: String, left: Float, top: Float, right: Float) = OcrLine(text, left, top, right, top + .02f)
    private fun page(lines: List<OcrLine>, text: String = lines.joinToString("\n") { it.text }) =
        ScanPage(1, "", text, lines = lines)

    @Test fun identifiesActualTableAndRetainsAllAmountsWithoutComputingNewOnes() {
        val lines = listOf(line("COMERCIO", .1f, .1f, .5f),
            line("Cant.", .1f, .2f, .18f), line("Descripción", .25f, .2f, .5f), line("Importe", .8f, .2f, .95f),
            line("2", .1f, .3f, .13f), line("Café", .25f, .3f, .35f), line("10.00", .8f, .3f, .95f),
            line("TOTAL", .6f, .4f, .75f), line("S/ 10.00", .8f, .4f, .95f))
        val result = ReceiptStructure.rows(page(lines))
        assertEquals(listOf(ReceiptStructure.Kind.HEADING, ReceiptStructure.Kind.TABLE_HEADER,
            ReceiptStructure.Kind.ITEM, ReceiptStructure.Kind.TOTAL), result.map { it.kind })
        assertEquals(lines.map { it.text }.sorted(), result.flatMap { it.cells }.map { it.text }.sorted())
        assertEquals(3, result[1].columns.size)
    }

    @Test fun incompleteGeometryFallsBackInsteadOfDroppingUnpositionedText() {
        val p = page(listOf(line("Negocio", .1f, .1f, .4f)), "Negocio\nDato que falta en geometría\nTOTAL 10.00")
        val result = ReceiptStructure.rows(p).flatMap { it.cells }.joinToString("\n") { it.text }
        assertTrue(result.contains("Dato que falta en geometría"))
        assertTrue(result.contains("TOTAL 10.00"))
    }

    @Test fun fallbackKeepsEveryNonSpaceCharacterIncludingAccentsAndLongCodes() {
        val original = "Compañía\nCant.  Descripción  Importe\n2  Piñón azul ABC-123/5  12,00\nIGV: 2,16\nTOTAL: 14,16"
        val rows = ReceiptStructure.rows(ScanPage(1, "", original))
        assertEquals(original.filterNot(Char::isWhitespace), rows.flatMap { it.cells }.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }

    @Test fun ambiguousHeaderIsKeptAsOrdinaryText() {
        val rows = ReceiptStructure.rows(ScanPage(1, "", "Comercio\nDescripción de la empresa\nDirección: Lima"))
        assertTrue(rows.none { it.kind == ReceiptStructure.Kind.TABLE_HEADER })
    }

    @Test fun duplicateProductsRemainSeparateRows() {
        val lines = listOf(line("Cant.", .1f, .1f, .18f), line("Descripción", .25f, .1f, .5f), line("Importe", .8f, .1f, .95f),
            line("Producto", .25f, .2f, .5f), line("Producto", .25f, .3f, .5f))
        assertEquals(2, ReceiptStructure.rows(page(lines)).count { it.kind == ReceiptStructure.Kind.ITEM })
    }

    @Test fun splitTicketHeaderDoesNotBecomeAnAmountTotal() {
        val lines = listOf(line("Cant.", .05f, .1f, .15f), line("Cod", .2f, .1f, .3f),
            line("P/U", .65f, .1f, .75f), line("TOTAL", .8f, .1f, .95f),
            line("Descripción", .2f, .13f, .55f), line("Producto", .2f, .2f, .55f),
            line("10.00", .8f, .2f, .95f))
        val rows = ReceiptStructure.rows(page(lines))
        assertEquals(2, rows.count { it.kind == ReceiptStructure.Kind.TABLE_HEADER })
        assertTrue(rows.none { it.kind == ReceiptStructure.Kind.TOTAL })
        assertEquals(ReceiptStructure.Kind.ITEM, rows.last().kind)
        assertTrue(rows.first().columns.first { it.label == "P/U" }.numeric)
    }

    @Test fun longRightAlignedPriceIsNotMovedIntoTheDescriptionColumn() {
        val columns = listOf(ReceiptStructure.Column("Cant", 0f, .15f, true),
            ReceiptStructure.Column("Descripción", .15f, .66f, false),
            ReceiptStructure.Column("P/U", .66f, .84f, true), ReceiptStructure.Column("Total", .84f, 1f, true))
        assertEquals(2, ReceiptStructure.columnIndex(ReceiptStructure.Cell("123456.00", .62f, .80f), columns))
        assertEquals(3, ReceiptStructure.columnIndex(ReceiptStructure.Cell("123456.00", .86f, .98f), columns))
        assertEquals(1, ReceiptStructure.columnIndex(ReceiptStructure.Cell("Producto de descripción larga", .20f, .72f), columns))
    }
}

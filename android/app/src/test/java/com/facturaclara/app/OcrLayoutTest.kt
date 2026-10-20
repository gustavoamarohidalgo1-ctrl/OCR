package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutTest {
    @Test
    fun aLineOfFiguresWithoutLabelKeepsItsAmountsTogether() {
        val descriptionA = OcrLine("Papel A4", .10f, .30f, .45f, .33f)
        val descriptionB = OcrLine("Archivador", .10f, .36f, .45f, .39f)
        val quantity = OcrLine("2.00", .62f, .42f, .65f, .45f)
        val amount = OcrLine("40.00", .80f, .42f, .92f, .45f)
        val rows = OcrLayout.rows(listOf(descriptionA, descriptionB, quantity, amount))
        assertEquals(listOf(listOf(descriptionA), listOf(descriptionB), listOf(quantity, amount)), rows)
    }

    @Test
    fun columnMajorOcrIsReconstructedAsVisualRows() {
        val descriptionA = OcrLine("Papel A4", .10f, .30f, .45f, .33f)
        val descriptionB = OcrLine("Archivador", .10f, .36f, .45f, .39f)
        val quantityA = OcrLine("2", .62f, .301f, .65f, .331f)
        val quantityB = OcrLine("4", .62f, .358f, .65f, .388f)
        val amountA = OcrLine("60.00", .80f, .302f, .92f, .332f)
        val amountB = OcrLine("40.00", .80f, .361f, .92f, .391f)
        val rows = OcrLayout.rows(listOf(descriptionA, descriptionB, amountB, amountA, quantityB, quantityA))
        assertEquals(listOf(listOf(descriptionA, quantityA, amountA), listOf(descriptionB, quantityB, amountB)), rows)
    }

    @Test
    fun nearbyLinesInTheSameColumnRemainSeparate() {
        // The vertical centers are close enough for a row, but horizontal overlap
        // proves these are two successive lines of the same description column.
        val first = OcrLine("Descripción primera línea", .1f, .30f, .6f, .34f)
        val next = OcrLine("continuación de descripción", .1f, .322f, .6f, .362f)
        assertEquals(listOf(listOf(first), listOf(next)), OcrLayout.rows(listOf(next, first)))
        assertEquals("Descripción primera línea\ncontinuación de descripción", OcrLayout.text(listOf(next, first)))
    }

    @Test
    fun repeatedAmountsAreKeptAsDistinctCells() {
        val first = OcrLine("10.00", .8f, .2f, .9f, .23f)
        val second = OcrLine("10.00", .8f, .3f, .9f, .33f)
        val rows = OcrLayout.rows(listOf(second, first))
        assertEquals(2, rows.size)
        assertEquals(listOf(first, second), rows.flatten())
    }

    @Test
    fun tiltedBaselinesKeepEachPriceBesideItsOwnProduct() {
        val first = OcrLine("Producto uno", .1f, .30f, .4f, .32f, -.05f)
        val second = OcrLine("Producto dos", .1f, .34f, .4f, .36f, -.05f)
        val price1 = OcrLine("15.49", .8f, .27f, .9f, .29f, -.05f)
        val price2 = OcrLine("18.99", .8f, .31f, .9f, .33f, -.05f)
        assertEquals(listOf(listOf(first, price1), listOf(second, price2)),
            OcrLayout.rows(listOf(price2, first, price1, second)))
    }

    @Test
    fun noisyAngleOnShortAmountDoesNotMoveItToTheFollowingDescription() {
        val amount = OcrLine("0.70", .700f, .511f, .775f, .540f, -.125f)
        val description = OcrLine("Primer complemento", .256f, .529f, .427f, .549f, -.008f)
        val nextDescription = OcrLine("Segundo complemento", .255f, .549f, .440f, .572f, -.017f)
        assertEquals(listOf(listOf(description, amount), listOf(nextDescription)),
            OcrLayout.rows(listOf(amount, nextDescription, description)))
    }

    @Test
    fun amountWaitsForTheBestDescriptionIncludingRowsNotYetVisitedVertically() {
        // Geometry of the rotated photo: the price center lies between two
        // modifiers. It fits the earlier one tolerably, but the later one best.
        val previous = OcrLine("- American Ch", .256f, .508f, .429333f, .529f, .017061f)
        val description = OcrLine("E Green Chile", .256f, .529f, .426667f, .549f, -.007959f)
        val next = OcrLine("S Santa Fe Sce", .254667f, .549f, .440f, .572f, -.016806f)
        val amount = OcrLine("$0.70", .700f, .511f, .774667f, .540f, -.125f)
        val expected = listOf(listOf(previous), listOf(description, amount), listOf(next))
        val input = listOf(previous, amount, description, next)
        assertEquals(expected, OcrLayout.rows(input))
        assertEquals(expected, OcrLayout.rows(input.reversed()))
        assertEquals(expected, OcrLayout.rows(listOf(next, description, previous, amount)))
    }

    @Test
    fun positiveTiltKeepsAnAmountWithItsEarlierDescription() {
        val previous = OcrLine("Primera fila", .10f, .28f, .40f, .30f, .06f)
        val description = OcrLine("Segunda fila", .10f, .32f, .40f, .34f, .06f)
        val next = OcrLine("Tercera fila", .10f, .36f, .40f, .38f, .06f)
        val amount = OcrLine("8.50", .80f, .355f, .88f, .385f, .20f)
        assertEquals(listOf(listOf(previous), listOf(description, amount), listOf(next)),
            OcrLayout.rows(listOf(amount, next, previous, description)))
    }

    @Test
    fun tiltedInvoiceWithFourColumnsKeepsAdjacentRowsDistinct() {
        val descriptionA = OcrLine("Descripción A", .10f, .300f, .48f, .325f, -.04f)
        val descriptionB = OcrLine("Descripción B", .10f, .335f, .48f, .360f, -.04f)
        val quantityA = OcrLine("2", .60f, .287f, .63f, .312f, -.04f)
        val quantityB = OcrLine("3", .60f, .322f, .63f, .347f, -.04f)
        val unitA = OcrLine("5.00", .68f, .283f, .75f, .308f, -.04f)
        val unitB = OcrLine("5.00", .68f, .318f, .75f, .343f, -.04f)
        val amountA = OcrLine("10.00", .80f, .2774f, .91f, .3024f, -.10f)
        val amountB = OcrLine("15.00", .80f, .3124f, .91f, .3374f, -.10f)
        val input = listOf(amountB, quantityA, descriptionB, unitB, amountA, descriptionA, unitA, quantityB)
        assertEquals(listOf(listOf(descriptionA, quantityA, unitA, amountA),
            listOf(descriptionB, quantityB, unitB, amountB)), OcrLayout.rows(input))
    }

    @Test
    fun intermediateColumnCannotChainIncompatibleRowsTogether() {
        // Amounts are placed by the column alignment instead; this guards label grouping.
        val left = OcrLine("Descripción", .1f, .290f, .4f, .310f)
        val middle = OcrLine("2", .6f, .300f, .63f, .320f)
        val right = OcrLine("UND", .8f, .309f, .9f, .329f)
        // Both neighboring pairs fit, but the left and right fragments do not.
        // Keep the closest pair and retain the other fragment on its own row.
        assertEquals(listOf(listOf(left), listOf(middle, right)),
            OcrLayout.rows(listOf(right, left, middle)))
    }

    @Test
    fun identicalInputOccurrencesAreNotDeduplicated() {
        val repeated = OcrLine("10.00", .8f, .3f, .9f, .33f)
        val input = listOf(repeated, repeated.copy())
        assertEquals(listOf(listOf(repeated), listOf(repeated)), OcrLayout.rows(input))
        assertEquals(input.size, OcrLayout.rows(input).flatten().size)
    }

    @Test
    fun largeInvoicePreservesOneThousandFragmentsInTheirRows() {
        val expected = (0 until 200).map { row ->
            (0 until 5).map { column ->
                val left = if (column == 0) .05f else .25f + column * .15f
                val right = left + if (column == 0) .25f else .055f
                val top = .04f + row * .0045f + (left + right) / 2f * .015f
                OcrLine("$row:$column", left, top, right, top + .0025f, .015f)
            }
        }
        val input = expected.flatten().reversed()
        val started = System.nanoTime()
        assertEquals(expected, OcrLayout.rows(input))
        println("OcrLayout: 1000 fragments / 200 rows in ${(System.nanoTime() - started) / 1_000_000.0} ms")
    }

    @Test
    fun metadataWithDifferentFontSizesPreservesEveryFragment() {
        val title = OcrLine("FACTURA", .1f, .1f, .4f, .15f)
        val number = OcrLine("F123-456789", .65f, .115f, .9f, .145f)
        val details = OcrLine("Cliente: Empresa de prueba", .1f, .20f, .7f, .23f)
        val input = listOf(details, number, title)
        val rows = OcrLayout.rows(input)
        assertEquals(input.toSet(), rows.flatten().toSet())
        assertEquals(input.size, rows.flatten().size)
        assertEquals(listOf(title, number), rows.first())
    }

    @Test
    fun legacyPageWithoutGeometryHasNoInventedRows() {
        assertTrue(OcrLayout.rows(ScanPage(0, "", "Texto antiguo").lines).isEmpty())
    }

    @Test
    fun rotatedStickerIsLaidOutAfterTheReceiptBody() {
        val label = OcrLine("1 COPA CHOCO-NAT", .10f, .40f, .40f, .42f)
        val price = OcrLine("0,47", .80f, .40f, .86f, .42f)
        val next = OcrLine("1 12 HUEVOS GRS", .10f, .43f, .40f, .45f)
        val nextPrice = OcrLine("1,09", .80f, .43f, .86f, .45f)
        // A sticker printed across the receipt at a clearly different angle, two lines.
        val sticker = OcrLine("Ticket de mercadona de 2004", .45f, .38f, .78f, .47f, .25f)
        val stickerNext = OcrLine("encontrado en casa", .47f, .44f, .76f, .52f, .24f)
        val rows = OcrLayout.rows(listOf(sticker, nextPrice, label, stickerNext, next, price))
        assertEquals(listOf(listOf(label, price), listOf(next, nextPrice)), rows.take(2))
        assertEquals(setOf(sticker, stickerNext), rows.drop(2).flatten().toSet())
    }

    @Test
    fun verticalMarginTextDoesNotJoinAReceiptRow() {
        val label = OcrLine("SubTotal", .20f, .50f, .35f, .52f)
        val amount = OcrLine("6.98", .60f, .50f, .66f, .52f)
        val margin = OcrLine("CASH GIVEAWAY ON BACK", .70f, .30f, .72f, .70f)
        val rows = OcrLayout.rows(listOf(margin, amount, label))
        assertEquals(listOf(listOf(label, amount), listOf(margin)), rows)
    }

    @Test
    fun curvedPaperAmountsJoinTheirLabels() {
        // Geometry recorded from a curved receipt photo: the amount column sags below
        // the labels, and slope extrapolation alone left each amount on its own row.
        val tax = OcrLine("TAX 8.25%", .168f, .702f, .524f, .739f, -.021f)
        val taxAmount = OcrLine(".17", .877f, .716f, .985f, .745f)
        val due = OcrLine("Amount Due", .164f, .738f, .564f, .781f, -.028f)
        val dueAmount = OcrLine("$2.17", .772f, .744f, .983f, .787f)
        val cash = OcrLine("Cash Tender", .161f, .813f, .599f, .855f, -.017f)
        val cashAmount = OcrLine("$3.00", .773f, .837f, .985f, .868f)
        val change = OcrLine("Change", .163f, .855f, .403f, .893f, -.017f)
        val changeAmount = OcrLine("$.83", .816f, .875f, .987f, .907f)
        val rows = OcrLayout.rows(listOf(changeAmount, cash, tax, dueAmount, change, taxAmount, cashAmount, due))
        assertEquals(listOf(listOf(tax, taxAmount), listOf(due, dueAmount), listOf(cash, cashAmount),
            listOf(change, changeAmount)), rows)
    }

    @Test
    fun twoAmountColumnsKeepOneAmountEachPerRow() {
        val first = OcrLine("Arroz", .10f, .30f, .40f, .32f)
        val second = OcrLine("Azúcar", .10f, .34f, .40f, .36f)
        val unitA = OcrLine("2.50", .60f, .30f, .68f, .32f)
        val unitB = OcrLine("3.10", .60f, .34f, .68f, .36f)
        val totalA = OcrLine("5.00", .82f, .30f, .90f, .32f)
        val totalB = OcrLine("6.20", .82f, .34f, .90f, .36f)
        assertEquals(listOf(listOf(first, unitA, totalA), listOf(second, unitB, totalB)),
            OcrLayout.rows(listOf(totalB, unitA, second, totalA, first, unitB)))
    }

    @Test
    fun amountsAreRecognizedWithoutCatchingCodesOrQuantities() {
        listOf("12.00", "0,47", "$2.17", "S/.50", ".17", "1.01-", "€3,10", "S/1,250.00", "1.250,00", "12,345.60")
            .forEach { assertTrue(it, OcrLayout.isAmount(it)) }
        listOf("2", "20100070970", "12:49", "8.25%", "TOTAL", "1.234.567").forEach { assertFalse(it, OcrLayout.isAmount(it)) }
    }

    @Test
    fun longLineOnCurvedPaperStaysInTheBody() {
        // A wide description whose local baseline differs by a small angle from the page.
        // Its drift along the width exceeds 1.5 text heights, but the angle is small.
        val title = OcrLine("PAPEL BOND A4 75 G PAQUETE X 500 HOJAS", .05f, .40f, .80f, .42f, .06f)
        val others = (1..4).map { OcrLine("Fila $it", .05f, .40f + it * .05f, .40f, .42f + it * .05f) }
        val rows = OcrLayout.rows(others + title)
        assertEquals(listOf(title), rows.first())
        assertEquals(others.map { listOf(it) }, rows.drop(1))
    }

    @Test
    fun fragmentWithAnOutlierAngleStillJoinsItsPrintedLine() {
        // Recorded geometry from a curved receipt: ML Kit reported a steep angle for the
        // short date, which used to pull it into the next line.
        val time = OcrLine("12:49 PM", .805f, .069f, .972f, .111f, -.067f)
        val terminal = OcrLine("L1 T1", .495f, .094f, .588f, .134f, -.072f)
        val date = OcrLine("2012-04-27", .189f, .115f, .357f, .179f, -.263f)
        val guest = OcrLine("YOUR GUEST NUMBER IS", .357f, .125f, .760f, .177f, -.057f)
        val number = OcrLine("12", .523f, .171f, .591f, .206f, -.100f)
        val store = OcrLine("IN-N-OUT BURGER SANTA CLARA", .280f, .238f, .825f, .291f, -.043f)
        val rows = OcrLayout.rows(listOf(guest, store, date, number, time, terminal))
        assertEquals(listOf(date, terminal, time), rows.first())
        assertEquals(listOf(guest), rows[1])
    }

    @Test
    fun singleCurledBodyLineIsNotTreatedAsOverlay() {
        // One long description bends by a large angle; nothing else shares that angle.
        val curled = OcrLine("PAN INTEGRAL 500G MOLDE GRANDE", .10f, .300f, .55f, .325f, .15f)
        val body = (0 until 6).map { OcrLine("Fila $it", .10f, .35f + it * .04f, .40f, .37f + it * .04f) }
        val rows = OcrLayout.rows(body.reversed() + curled)
        assertEquals(listOf(curled), rows.first())
    }

    @Test
    fun longCurledDescriptionStillPairsWithItsQuantity() {
        // The long fragment keeps its measured angle; only short fragments lose theirs.
        val description = OcrLine("PAN INTEGRAL 500G", .10f, .300f, .50f, .325f, .11f)
        val quantity = OcrLine("2 UN", .60f, .3385f, .70f, .3635f)
        val flat = (0 until 4).map { OcrLine("Concepto plano $it", .10f, .45f + it * .05f, .60f, .475f + it * .05f) }
        val rows = OcrLayout.rows(flat + quantity + description)
        assertEquals(listOf(description, quantity), rows.first())
    }

    @Test
    fun noisyAmountAngleDoesNotSplitAnInvoiceTableRow() {
        // Recorded from an A4 invoice: the importe "50.00" reports a slight angle that,
        // extrapolated across the table, used to separate the quantity from its description.
        val header = listOf(OcrLine("CANT.", .104f, .548f, .149f, .573f), OcrLine("DESCRIPCIÓN", .310f, .544f, .414f, .574f),
            OcrLine("PRECIO UNITARIO", .571f, .549f, .703f, .573f), OcrLine("IMPORTE", .850f, .549f, .918f, .573f))
        val first = listOf(OcrLine("1", .122f, .609f, .126f, .623f), OcrLine("Talla pequeña traje de luces en rojo", .182f, .609f, .447f, .629f),
            OcrLine("100.00", .654f, .609f, .703f, .625f), OcrLine("100.00", .870f, .609f, .919f, .625f))
        val second = listOf(OcrLine("2", .122f, .660f, .131f, .676f), OcrLine("Mui grande churrolito", .183f, .660f, .339f, .681f),
            OcrLine("25.00", .662f, .660f, .703f, .677f), OcrLine("50.00", .877f, .657f, .918f, .678f, -.031f))
        val third = listOf(OcrLine("3", .123f, .713f, .131f, .729f), OcrLine("Equipaje de Fútbol", .183f, .708f, .320f, .733f, -.014f),
            OcrLine("5.00", .671f, .712f, .702f, .730f), OcrLine("15.00", .879f, .712f, .919f, .730f))
        val rows = OcrLayout.rows((header + first + second + third).reversed())
        assertEquals(listOf(header, first, second, third), rows)
    }
}

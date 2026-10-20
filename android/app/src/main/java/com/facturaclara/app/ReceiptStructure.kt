package com.facturaclara.app

import java.text.Normalizer
import java.util.Locale

/** Layout only: no inferred quantities, prices, tax, merchant or customer values. */
object ReceiptStructure {
    enum class Kind { HEADING, DETAILS, TABLE_HEADER, ITEM, SUMMARY, TOTAL, NOTE }
    data class Cell(val text: String, val left: Float, val right: Float, val doubtful: Boolean = false)
    data class Column(val label: String, val left: Float, val right: Float, val numeric: Boolean)
    data class Row(val cells: List<Cell>, val kind: Kind, val columns: List<Column> = emptyList())

    /** The page's fragments have valid boxes and hold exactly the characters of its text. */
    fun hasGeometry(page: ScanPage): Boolean = page.lines.isNotEmpty() && page.lines.all {
        it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() &&
            it.left >= 0 && it.right <= 1 && it.left < it.right && it.top >= 0 && it.bottom <= 1 && it.top < it.bottom
    } && inventory(page.text) == inventory(page.lines.joinToString("\n") { it.text })

    fun rows(page: ScanPage): List<Row> {
        val geometric = hasGeometry(page)
        val source = if (geometric) {
            val left = page.lines.minOf { it.left }
            val span = (page.lines.maxOf { it.right } - left).coerceAtLeast(0.1f)
            OcrLayout.rows(page.lines).map { row -> row.map {
                Cell(it.text, ((it.left - left) / span).coerceIn(0f, 1f), ((it.right - left) / span).coerceIn(0f, 1f), it.doubtful)
            } }
        } else {
            // Older jobs do not have geometry. Retain every line and its spaces.
            // Unconfirmed text cannot be located without geometry: the whole page is flagged.
            val unconfirmed = page.blockers.isNotEmpty()
            val lines = PdfTextLayout.expandTabs(page.text).replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val width = lines.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
            lines.map { line ->
                val matches = Regex("\\S(?:.*?\\S)?(?=\\s{2,}|$)").findAll(line).toList()
                if (matches.size > 1) matches.map { Cell(it.value, it.range.first.toFloat() / width, (it.range.last + 1f) / width, unconfirmed) }
                else listOf(Cell(line, 0f, 1f, unconfirmed))
            }
        }
        val headerRows = mutableSetOf<Int>()
        source.indices.forEach { index ->
            val current = source[index].joinToString(" ") { it.text }
            if (isHeader(key(current))) headerRows += index
            else if (index < source.lastIndex) {
                val next = source[index + 1].joinToString(" ") { it.text }
                if (headerVocabulary(key(current)) && headerVocabulary(key(next)) && isHeader(key("$current $next"))) {
                    headerRows += index
                    headerRows += index + 1
                }
            }
        }
        var columns = emptyList<Column>()
        var inTable = false
        var afterTable = false
        var heading = false
        return source.mapIndexed { index, cells ->
            val text = key(cells.joinToString(" ") { it.text })
            val isHeader = index in headerRows
            val startsWithQuantity = inTable && cells.size > 1 && cells.first().text.trim().matches(Regex("\\d+(?:[.,]\\d+)?"))
            val summary = !startsWithQuantity && cells.any { isSummary(key(it.text)) }
            val hasAmount = Regex("\\d[.,]\\d{2,}").containsMatchIn(text)
            val total = !startsWithQuantity && hasAmount && cells.any { Regex("^(IMPORTE TOTAL|TOTAL A PAGAR|MONTO TOTAL|TOTAL)(?:\\b|:)").containsMatchIn(key(it.text)) }
            val kind = when {
                isHeader -> {
                    val candidate = makeColumns(cells)
                    if (candidate.isNotEmpty()) columns = candidate
                    inTable = true
                    Kind.TABLE_HEADER
                }
                inTable && summary -> { inTable = false; afterTable = true; if (total) Kind.TOTAL else Kind.SUMMARY }
                inTable -> Kind.ITEM
                total && !isHeader -> Kind.TOTAL
                summary -> Kind.SUMMARY
                !heading && text.isNotBlank() && !Regex("DEMO|FICTICI|MUESTRA").containsMatchIn(text) -> { heading = true; Kind.HEADING }
                afterTable -> Kind.NOTE
                else -> Kind.DETAILS
            }
            Row(cells, kind, if (kind == Kind.ITEM || (kind == Kind.TABLE_HEADER && cells.size == columns.size)) columns else emptyList())
        }
    }

    private fun isHeader(text: String) = Regex("\\b(DESCRIPCION|DESCRIP|DETALLE|PRODUCTO|ARTICULO)\\b").containsMatchIn(text) &&
        Regex("\\b(CANT|CANTIDAD|CODIGO|COD|UNIDAD|UM|U/M)\\b").containsMatchIn(text) &&
        Regex("\\b(PRECIO|IMPORTE|TOTAL|UNIT|VENTA|P/U)\\b").containsMatchIn(text)

    private fun headerVocabulary(text: String) = text.isNotBlank() &&
        text.replace(Regex("DESCRIPCION|DESCRIP|DETALLE|PRODUCTO|ARTICULO|CANTIDAD|CANT|CODIGO|COD|UNIDAD|U[./]?M|PRECIO|IMPORTE|TOTAL|UNITARIO|UNIT|VENTA|SUBTOTAL|P/U|P[.]?"), "")
            .all { it.isWhitespace() || it in ".:/|" }

    private fun makeColumns(cells: List<Cell>): List<Column> {
        if (cells.size < 2) return emptyList()
        return cells.mapIndexed { i, cell ->
            val left = if (i == 0) 0f else (cells[i - 1].right + cell.left) / 2f
            val right = if (i == cells.lastIndex) 1f else (cell.right + cells[i + 1].left) / 2f
            Column(cell.text, left, right, Regex("CANT|PRECIO|P/U|UNIT|IMPORTE|TOTAL|VALOR").containsMatchIn(key(cell.text)) && !key(cell.text).contains("UNIDAD"))
        }.takeIf { cols -> cols.all { it.right - it.left >= 0.035f } } ?: emptyList()
    }

    fun isNumericValue(text: String): Boolean = Regex("^(?:S/\\.?|US\\$|\\$|PEN|USD|EUR)?\\s*[-+]?\\d[\\d., ]*%?$").matches(text.trim())

    /** Right aligned amounts belong to the band where they end, even when long. */
    fun columnIndex(cell: Cell, columns: List<Column>): Int = if (isNumericValue(cell.text)) {
        columns.indexOfFirst { cell.right > it.left && cell.right <= it.right + .01f }
    } else {
        columns.indexOfFirst { cell.left >= it.left - .01f && cell.left < it.right }
    }

    private fun isSummary(text: String): Boolean = Regex(
        "^(SUB\\s*TOTAL|OP[. ]*GRAVAD|OPERACION|BASE IMPONIBLE|GRAVADA|EXONERAD|INAFECT|I[.]?G[.]?V|I[.]?V[.]?A|IMPUESTO|TOTAL|IMPORTE TOTAL|MONTO TOTAL|SON:|SON |DESCUENTO|EFECTIVO|VUELTO|FORMA DE PAGO|CONDICION|PAGADO|REPRESENTACION|OBSERVACION)"
    ).containsMatchIn(text)

    private fun key(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").uppercase(Locale.ROOT).trim()

    private fun inventory(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
        .filterNot(Char::isWhitespace).groupingBy { it }.eachCount()
}

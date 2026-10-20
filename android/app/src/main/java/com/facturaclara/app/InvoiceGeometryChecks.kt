package com.facturaclara.app

/** Validate the same visual rows used by the PDF without changing their contents. */
internal object InvoiceGeometryChecks {
    private val currency = Regex("US\\$|S/\\.?|PEN|USD|EUR|\\p{Sc}")
    private val amount = Regex("^[+\\-−]?(?:\\d[\\d., ]*|[.,]\\d+)%?$")

    private fun isAmount(text: String): Boolean =
        amount.matches(currency.replace(text, "").trim())

    fun inspect(text: String, lines: List<OcrLine>): List<String> {
        val rows = ReceiptStructure.rows(ScanPage(index = 0, imagePath = "", text = text, lines = lines))
        if (rows.none { it.kind == ReceiptStructure.Kind.TABLE_HEADER }) return emptyList()
        val orphanAmounts = rows.any { row ->
            if (row.kind != ReceiptStructure.Kind.ITEM) return@any false
            val fragments = row.cells.map { it.text.trim() }.filter { it.isNotEmpty() }
            fragments.any(::isAmount) && fragments.all {
                isAmount(it) || currency.replace(it, "").isBlank() || it in setOf("+", "-", "−")
            }
        }
        return if (orphanAmounts) listOf(
            "La tabla contiene una fila de importes sin una descripción asociada con claridad. Tome una nueva foto con las filas completas y bien alineadas."
        ) else emptyList()
    }
}

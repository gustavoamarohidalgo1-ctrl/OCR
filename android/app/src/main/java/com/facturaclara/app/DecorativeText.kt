package com.facturaclara.app

/**
 * Printed decoration, such as rows of asterisks framing a survey box, that the
 * recognizer turns into strings of X, K and *. It carries no receipt content.
 * Masked card numbers keep their digits and plain X masks have no asterisks,
 * so neither matches.
 */
object DecorativeText {
    private val glyphs = setOf('*', 'X', 'x', 'K', 'k')

    /** Reading text without decorative rows; text without geometry is returned unchanged. */
    fun withoutDecoration(reading: OfflineOcr.Reading): String =
        if (reading.lines.isEmpty() || reading.lines.none { isDecoration(it.text) }) reading.text
        else OcrLayout.text(reading.lines.filterNot { isDecoration(it.text) })

    fun isDecoration(text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        return visible.length >= 6 && visible.count { it == '*' } >= 2 &&
            visible.count { it in glyphs } >= visible.length * .9
    }
}

package com.facturaclara.app

/**
 * Decides when a page is a full sheet (an A4 or letter invoice) rather than a narrow
 * receipt, and maps its OCR geometry onto the output page. Sheets keep their original
 * arrangement in the PDF; receipts keep the rebuilt row-and-table layout.
 */
object SheetLayout {
    /** Receipts hold roughly 25 to 55 characters per line; printed sheets 90 or more. */
    const val SHEET_CHARACTERS_PER_LINE = 90f
    private const val MIN_FRAGMENTS = 8

    /** How many characters of the page's typical width fit across its text block. */
    fun lineCapacity(lines: List<OcrLine>): Float {
        val measured = lines.filter { it.text.trim().length >= 3 }
        if (measured.isEmpty()) return 0f
        val widths = measured.map { (it.right - it.left) / it.text.length }.sorted()
        val characterWidth = widths[widths.size / 2]
        if (characterWidth <= 0f) return 0f
        // The outermost fragment on each side is ignored, so one stray mark or cut-off word
        // beside a receipt cannot widen its span to a sheet's.
        val lefts = measured.map { it.left }.sorted()
        val rights = measured.map { it.right }.sorted()
        val skip = if (measured.size > 2) 1 else 0
        val span = (rights[rights.size - 1 - skip] - lefts[skip]).coerceAtLeast(0f)
        return span / characterWidth
    }

    fun isSheet(lines: List<OcrLine>): Boolean =
        lines.size >= MIN_FRAGMENTS && lineCapacity(lines) >= SHEET_CHARACTERS_PER_LINE

    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /** The largest box with the source page's aspect (width / height) centered in [area]. */
    fun fit(sourceAspect: Float, area: Box): Box {
        require(sourceAspect > 0f && area.width > 0f && area.height > 0f)
        val width = minOf(area.width, area.height * sourceAspect)
        val height = width / sourceAspect
        return Box(area.left + (area.width - width) / 2f, area.top, width, height)
    }
}

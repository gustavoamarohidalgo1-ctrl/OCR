package com.facturaclara.app

import java.text.BreakIterator
import java.util.Locale

/** Measured wrapping without trimming, dropping words, or splitting accent clusters. */
object PdfTextLayout {
    fun wrapLine(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        require(maxWidth.isFinite() && maxWidth > 0f) { "El ancho del texto debe ser positivo." }
        if (text.isEmpty()) return listOf("")
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
        characters.setText(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var lastSpace = -1
            var next = characters.following(end)
            while (next != BreakIterator.DONE) {
                val measured = measure(text.substring(start, next))
                require(measured.isFinite() && measured >= 0f) { "La medida del texto no es válida." }
                if (measured > maxWidth && end > start) break
                val previousBoundary = end
                end = next
                if (text.substring(previousBoundary, next).all { it.isWhitespace() }) {
                    lastSpace = next
                }
                // An exceptionally wide glyph must still make progress and stay intact.
                if (measured > maxWidth) break
                next = characters.following(end)
            }
            val stop = if (end < text.length && lastSpace > start) lastSpace else end
            check(stop > start) { "No se pudo dividir una línea de texto." }
            result.add(text.substring(start, stop))
            start = stop
        }
        return result
    }

    /** Hard line breaks, including trailing empty lines, remain separate layout lines. */
    fun wrapText(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = mutableListOf<String>()
        var start = 0
        while (true) {
            val newline = normalized.indexOf('\n', start)
            if (newline < 0) {
                lines.addAll(wrapLine(normalized.substring(start), maxWidth, measure))
                return lines
            }
            lines.addAll(wrapLine(normalized.substring(start, newline), maxWidth, measure))
            start = newline + 1
        }
    }

    /** PDF drawing has no tab stops; expand tabs while retaining their visual columns. */
    fun expandTabs(text: String, tabSize: Int = 4): String {
        require(tabSize > 0)
        val result = StringBuilder()
        var column = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            when (codePoint) {
                '\t'.code -> {
                    val spaces = tabSize - column % tabSize
                    repeat(spaces) { result.append(' ') }
                    column += spaces
                }
                '\n'.code, '\r'.code -> {
                    result.appendCodePoint(codePoint)
                    column = 0
                }
                else -> {
                    result.appendCodePoint(codePoint)
                    column += 1
                }
            }
            offset += Character.charCount(codePoint)
        }
        return result.toString()
    }
}

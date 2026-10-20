package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExporterTest {
    private val monospace: (String) -> Float = { text -> text.codePointCount(0, text.length).toFloat() }

    @Test fun wrappingKeepsEveryCharacterIncludingSpaces() {
        val original = "  Descripción     Cantidad    Importe  "
        val lines = PdfTextLayout.wrapLine(original, 12f, monospace)
        assertEquals(original, lines.joinToString(""))
        assertTrue(lines.all { monospace(it) <= 12f })
    }

    @Test fun unbrokenLongWordsAreContinuedWithoutTruncation() {
        val original = "ABC123".repeat(500)
        val lines = PdfTextLayout.wrapLine(original, 31f, monospace)
        assertEquals(original, lines.joinToString(""))
        assertTrue(lines.size > 90)
        assertTrue(lines.all { it.length <= 31 })
    }

    @Test fun explicitAndTrailingLineBreaksArePreserved() {
        assertEquals(listOf("Árbol", "", "IGV 18%", ""),
            PdfTextLayout.wrapText("Árbol\r\n\r\nIGV 18%\n", 50f, monospace))
    }

    @Test fun accentsAndSurrogatePairsRemainIntact() {
        val original = "Descripción: café, niño, a\u0301; 😀"
        val lines = PdfTextLayout.wrapLine(original, 7f, monospace)
        assertEquals(original, lines.joinToString(""))
        assertTrue(lines.none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(lines.none { it.firstOrNull()?.isLowSurrogate() == true })
        assertTrue(lines.none { it.startsWith("\u0301") })
    }

    @Test fun wrappingUsesActualMeasurementRatherThanCharacterCount() {
        val measure: (String) -> Float = { text -> text.fold(0) { width, char -> width + if (char == 'W') 3 else 1 }.toFloat() }
        assertEquals(listOf("WW", "iiW", "W"), PdfTextLayout.wrapLine("WWiiWW", 6f, measure))
    }

    @Test fun oversizedGlyphStillMakesProgress() {
        assertEquals(listOf("😀", "A"), PdfTextLayout.wrapLine("😀A", 1f) { it.length * 10f })
    }

    @Test fun tabColumnsRestartAtNewlines() {
        assertEquals("A   B\n    C", PdfTextLayout.expandTabs("A\tB\n\tC"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidWidthIsRejected() {
        PdfTextLayout.wrapLine("A", 0f, monospace)
    }
}

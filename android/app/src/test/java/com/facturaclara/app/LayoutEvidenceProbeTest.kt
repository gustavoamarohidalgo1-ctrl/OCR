package com.facturaclara.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer

/**
 * Offline diagnostic, skipped unless build/layout-probe exists. It replays OCR geometry
 * saved by a device batch through the current OcrLayout (after removing decorative
 * rows, as the worker does) and compares the text with independent references.
 * References never reach production code.
 */
class LayoutEvidenceProbeTest {
    @Test fun replaySavedGeometryAgainstReferences() {
        val directory = File("build/layout-probe")
        assumeTrue(directory.isDirectory)
        val report = StringBuilder()
        var edits = 0
        var total = 0
        directory.listFiles { file -> file.name.endsWith(".lines.tsv") }.orEmpty().sortedBy { it.name }.forEach { file ->
            val name = file.name.removeSuffix(".lines.tsv")
            val pages = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { row ->
                val cells = row.split('\t')
                cells[0].toInt() to OcrLine(cells[1], cells[2].toFloat(), cells[3].toFloat(), cells[4].toFloat(),
                    cells[5].toFloat(), cells[6].toFloat(), cells[7].takeIf { it.isNotBlank() }?.toFloat())
            }.groupBy({ it.first }, { it.second })
            val text = pages.toSortedMap().values.joinToString("\n\n") { lines ->
                OcrLayout.text(lines.filterNot { DecorativeText.isDecoration(it.text) })
            }
            val expected = characters(File(directory, "$name.reference.txt").readText(Charsets.UTF_8))
            val distance = levenshtein(expected, characters(text))
            edits += distance
            total += expected.length
            val capacity = pages.values.maxOf { SheetLayout.lineCapacity(it) }
            val sheet = pages.values.any { SheetLayout.isSheet(it) }
            report.append("%-36s CER %6.2f%%  capacidad %5.1f%s\n".format(name, distance * 100.0 / expected.length,
                capacity, if (sheet) "  HOJA" else ""))
        }
        report.append("TOTAL CER %.2f%% (%d/%d)\n".format(edits * 100.0 / total, edits, total))
        File(directory, "result.txt").writeText(report.toString(), Charsets.UTF_8)
    }

    private fun characters(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).filterNot(Char::isWhitespace)

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}

package com.facturaclara.app

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Offline diagnostic, skipped unless build/invoice-probe exists: lays out OCR geometry
 * saved from device runs with the current OcrLayout and writes the text to out/, so
 * layout changes can be scored against reference texts outside the test.
 */
class InvoiceLayoutProbeTest {
    @Test fun layOutSavedInvoiceGeometry() {
        val directory = File("build/invoice-probe")
        assumeTrue(directory.isDirectory)
        val output = File(directory, "out").apply { mkdirs() }
        directory.listFiles { file -> file.name.endsWith(".lines.tsv") }.orEmpty().forEach { file ->
            val pages = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { row ->
                val cells = row.split('\t')
                cells[0].toInt() to OcrLine(cells[1], cells[2].toFloat(), cells[3].toFloat(), cells[4].toFloat(),
                    cells[5].toFloat(), cells[6].toFloat(), cells[7].takeIf { it.isNotBlank() }?.toFloat())
            }.groupBy({ it.first }, { it.second })
            val text = pages.toSortedMap().values.joinToString("\n\n") { lines ->
                OcrLayout.text(lines.filterNot { DecorativeText.isDecoration(it.text) })
            }
            File(output, file.name.removeSuffix(".lines.tsv") + ".txt").writeText(text, Charsets.UTF_8)
        }
    }
}

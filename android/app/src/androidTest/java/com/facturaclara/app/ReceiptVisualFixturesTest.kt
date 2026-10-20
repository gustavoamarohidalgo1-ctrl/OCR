package com.facturaclara.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/** Deterministic visual fixtures. These tests assess PDF layout, not OCR accuracy. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ReceiptVisualFixturesTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val outputFolder: File get() = File(context.filesDir, "visual-samples").apply { mkdirs() }

    @Test fun fiveProductReceiptRetainsAllTextAndCreatesVisualSample() {
        val fixture = DemoReceiptFactory.fixture()
        assertTrue(fixture.text.contains("S/ 229.00"))
        assertTrue(fixture.text.contains("S/ 41.22"))
        assertTrue(fixture.text.contains("S/ 270.22"))
        validateBoxes(fixture)
        val pdf = generate("visual-boleta-ficticia", "boleta-ficticia.png", fixture, "boleta.pdf")
        val (pages, text) = extractText(pdf)
        assertEquals("Una boleta de cinco productos debe caber en una hoja.", 1, pages)
        assertTextRetained(fixture, text)
        val original = DemoReceiptFactory.create()
        try {
            File(outputFolder, "boleta-origen.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { original.recycle() }
    }

    @Test fun seventyProductsPaginateWithoutLosingTextAndCreateVisualSample() {
        val fixture = DemoReceiptFactory.fixture(productCount = 70)
        validateBoxes(fixture)
        assertTrue(fixture.text.contains("S/ 3206.00"))
        assertTrue(fixture.text.contains("S/ 577.08"))
        assertTrue(fixture.text.contains("S/ 3783.08"))
        val pdf = generate("visual-factura-extensa-ficticia", "factura-extensa-ficticia.png", fixture, "factura-extensa.pdf")
        val (pages, text) = extractText(pdf)
        assertTrue("Setenta productos necesitan paginación legible.", pages > 1)
        assertTrue("La muestra no debe dejar la leyenda sola en una quinta hoja.", pages <= 4)
        assertTextRetained(fixture, text)
        for (number in 1..70) {
            val identifier = "Artículo ${number.toString().padStart(3, '0')}"
            assertTrue("Falta $identifier en el PDF.", compact(text).contains(compact(identifier)))
        }
    }

    private fun generate(id: String, name: String, fixture: DemoReceiptFactory.Fixture, outputName: String): File {
        val invoice = Invoice(id = id, name = name, createdAt = 0L, sourcePath = "", mime = "image/png",
            status = "processing", pages = listOf(ScanPage(index = 1, imagePath = "", text = fixture.text, lines = fixture.lines)), isDesignExample = true)
        val generated = PdfExporter(context).generate(invoice)
        // Kept on device so the host can inspect rendered pages after the test.
        return generated.copyTo(File(outputFolder, outputName), overwrite = true)
    }

    private fun extractText(pdf: File): Pair<Int, String> {
        val text = StringBuilder()
        var pageCount = 0
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                pageCount = renderer.pageCount
                for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    assertTrue("La salida digital no debe incrustar la imagen escaneada.", page.imageContents.isEmpty())
                    text.append(page.textContents.joinToString("\n") { it.text }).append('\n')
                }
            }
        }
        return pageCount to text.toString()
    }

    private fun validateBoxes(fixture: DemoReceiptFactory.Fixture) {
        assertTrue(fixture.lines.isNotEmpty())
        fixture.lines.forEach { line ->
            assertTrue("Caja fuera de rango: ${line.text}", line.left >= 0f && line.top >= 0f && line.right <= 1f && line.bottom <= 1f)
            assertTrue("Caja sin área: ${line.text}", line.right > line.left && line.bottom > line.top)
        }
    }

    private fun assertTextRetained(fixture: DemoReceiptFactory.Fixture, output: String) {
        val normalized = compact(output)
        val expected = fixture.lines.map { compact(it.text) }.groupingBy { it }.eachCount()
        expected.forEach { (run, count) ->
            var matches = 0
            var offset = 0
            while (offset <= normalized.length - run.length) {
                val index = normalized.indexOf(run, offset)
                if (index < 0) break
                matches++
                offset = index + run.length
            }
            assertTrue("Texto perdido o incompleto ($matches de $count): $run", matches >= count)
        }
    }

    private fun compact(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), "")
}

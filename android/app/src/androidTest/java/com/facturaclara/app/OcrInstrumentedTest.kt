package com.facturaclara.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real bundled-model tests. Run on an emulator/device with Wi-Fi and mobile data off. */
@RunWith(AndroidJUnit4::class)
class OcrInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = Repository(context)

    @Test
    fun detailedDemoWithAmbiguousTableRowsIsExportedForReview() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET))
        withDocument("image/png", "original.png") { invoice, source ->
            val bitmap = DemoReceiptFactory.create()
            try {
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            assertEquals(diagnostics(result), "review", result.status)
            assertEquals(1, result.pages.size)
            val page = result.pages.single()
            assertTrue(page.text.contains("270.22"))
            assertTrue(page.text.contains("00001248"))
            assertEquals("La transcripción debe conservar todos los caracteres impresos de la muestra.",
                textInventory(DemoReceiptFactory.fixture().text), textInventory(page.text))
            // The new readers recover the quantities, but some amounts remain
            // on separate table rows. Complete characters do not establish a
            // correct association between product and price.
            assertTrue(page.blockers.any { it.contains("tabla") && it.contains("descripción") })
            assertTrue(File(page.imagePath).isFile)
            assertTrue("El OCR debe conservar la posición del texto.", page.lines.isNotEmpty())
            // Geometry is returned in the model's block order; the app and PDF
            // deliberately reconstruct visual rows from those fragments.
            assertEquals("La geometría debe reconstruir exactamente la transcripción primaria.",
                page.text, OcrLayout.text(page.lines))
            assertTrue(page.lines.all { line ->
                listOf(line.left, line.top, line.right, line.bottom).all { it.isFinite() && it in 0f..1f } &&
                    line.left < line.right && line.top < line.bottom
            })
            assertNotNull("Unconfirmed text is exported for review.", result.pdfPath)
            assertTrue(File(repository.documentDir(invoice.id), "result.pdf").isFile)
        }
    }

    @Test
    fun singleColumnControlWithMergedIssuerWordsIsRejected() {
        val expected = listOf(
            "DEMOSTRACION - DOCUMENTO FICTICIO",
            "SUMINISTROS EJEMPLO S.A.C.",
            "RUC: 20123456789",
            "FACTURA F123-456789",
            "Fecha: 17/10/2026",
            "Cliente: Empresa de prueba",
            "Moneda: PEN",
            "Descripcion / Cantidad / Importe",
            "Papel A4 / 2 / 60.00",
            "Archivador / 4 / 40.00",
            "Subtotal: S/ 100.00",
            "IGV: S/ 18.00",
            "TOTAL: S/ 118.00",
            "Muestra ficticia para probar la aplicacion.",
        ).joinToString("\n")
        withDocument("image/png", "original.png") { invoice, source ->
            val bitmap = Bitmap.createBitmap(1600, 2100, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 44f
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                }
                expected.lines().forEachIndexed { line, text ->
                    canvas.drawText(text, 100f, 150f + line * 105f, paint)
                }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            assertEquals(diagnostics(result), "review", result.status)
            val page = result.pages.single()
            val comparison = OcrConsistency.compare(expected, page.text)
            // 0.2.0 called this exact because it erased every space before
            // comparison. The bundled model joins EJEMPLO + S.A.C.; matching
            // characters is insufficient to claim faithful transcription.
            assertFalse("La pérdida del límite entre palabras debe detectarse.", comparison.contentMatches)
            assertTrue("El control debe conservar todas las cantidades.", comparison.numbersMatch)
            assertTrue(page.blockers.any { it.contains("palabras") })
            assertNotNull("Unconfirmed text is exported for review.", result.pdfPath)
        }
    }

    @Test
    fun verySmallImageIsNeverReady() {
        withDocument("image/png", "original.png") { invoice, file ->
            val bitmap = Bitmap.createBitmap(260, 340, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
                canvas.drawText("FACTURA F001-123", 10f, 50f, paint)
                canvas.drawText("TOTAL 118.00", 10f, 100f, paint)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            val page = result.pages.single()
            assertTrue(page.blockers.isNotEmpty())
            if (page.text.isBlank()) {
                assertEquals("needs_recapture", result.status)
                assertNull(result.pdfPath)
            } else {
                assertEquals("review", result.status)
                assertNotNull("Unconfirmed text is exported for review.", result.pdfPath)
            }
        }
    }

    @Test
    fun fiftyOnePagePdfIsRejectedBeforeAnyPageIsSkipped() {
        withDocument("application/pdf", "original.pdf") { invoice, file ->
            val pdf = PdfDocument()
            try {
                repeat(51) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 200, index + 1).create())
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            assertEquals("failed", result.status)
            assertTrue(result.error.orEmpty().contains("51"))
            assertTrue(result.pages.isEmpty())
            assertNull(result.pdfPath)
        }
    }

    @Test
    fun twoPagePdfTranscribesBothPages() {
        withDocument("application/pdf", "original.pdf") { invoice, file ->
            val pdf = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 23f }
                    listOf("FACTURA F001-0000000${index + 1}", "EMPRESA DE PRUEBA", "RUC 20123456789",
                        "Subtotal: 100.00", "IGV: 18.00", "TOTAL: 118.00").forEachIndexed { line, text ->
                        page.canvas.drawText(text, 45f, 90f + line * 65f, paint)
                    }
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            assertTrue(diagnostics(result), result.status in setOf("ready", "review"))
            assertEquals(2, result.pages.size)
            assertEquals(listOf(0, 1), result.pages.map { it.index })
            assertTrue(result.pages[0].text.contains("00000001"))
            assertTrue(result.pages[1].text.contains("00000002"))
            assertTrue(result.pages.all { File(it.imagePath).isFile })
            if (result.status == "ready") {
                assertNotNull(result.pdfPath)
                assertTrue(result.pages.all { it.blockers.isEmpty() })
            } else {
                assertNotNull(result.pdfPath)
                assertTrue(result.pages.any { it.blockers.isNotEmpty() })
            }
        }
    }

    @Test
    fun passwordProtectedPdfHasExplicitFailureAndNoExport() {
        withDocument("application/pdf", "original.pdf") { invoice, file ->
            file.writeBytes(Base64.decode(LOCKED_PDF, Base64.DEFAULT))
            repository.enqueue(invoice.id)
            val result = awaitDocument(invoice.id)
            assertEquals("failed", result.status)
            assertTrue(result.error.orEmpty().contains("contraseña"))
            assertTrue(result.pages.isEmpty())
            assertNull(result.pdfPath)
        }
    }

    private fun awaitDocument(id: String): Invoice {
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val invoice = repository.get(id)!!
            if (invoice.status in setOf("ready", "review", "needs_recapture", "failed")) return invoice
            Thread.sleep(150)
        }
        throw AssertionError("El procesamiento no terminó en 120 segundos: ${repository.get(id)?.status}")
    }

    private fun diagnostics(invoice: Invoice): String = buildString {
        append(invoice.error.orEmpty())
        invoice.pages.forEach { page ->
            append("\nPÁGINA ${page.index + 1}\n")
            append(page.blockers.joinToString("\n"))
            append("\nTEXTO:\n${page.text}\n")
        }
    }

    private fun textInventory(text: String): Map<Char, Int> =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
            .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }.groupingBy { it }.eachCount()

    private fun withDocument(mime: String, filename: String, block: (Invoice, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val directory = repository.documentDir(id)
        val source = File(directory, filename)
        val invoice = Invoice(id, "Documento de prueba", System.currentTimeMillis(), source.absolutePath, mime)
        repository.save(invoice)
        try {
            block(invoice, source)
        } catch (error: Throwable) {
            try {
                val evidence = File(requireNotNull(context.getExternalFilesDir(null)),
                    "ocr-regressions/${System.currentTimeMillis()}-$id")
                check(evidence.mkdirs())
                directory.listFiles().orEmpty().filter { file ->
                    file.isFile && (file.name in setOf("document.json", filename, "result.pdf") ||
                        (file.name.startsWith("page-") && file.extension == "png") ||
                        (file.name.startsWith("verification") && file.extension == "json"))
                }.forEach { it.copyTo(File(evidence, it.name)) }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\nOCR_REGRESSION_EVIDENCE=${evidence.absolutePath}\n")
                })
            } catch (_: Throwable) {
                // Diagnostics must never replace the original test failure.
            }
            throw error
        } finally { directory.deleteRecursively() }
    }

    companion object {
        // A synthetic blank PDF encrypted with "test-only-password"; it contains no user data.
        private const val LOCKED_PDF = "JVBERi0xLjMKJeLjz9MKMSAwIG9iago8PAovUHJvZHVjZXIgPDQ5NWI0MjY4NzM+Cj4+CmVuZG9iagoyIDAgb2JqCjw8Ci9UeXBlIC9QYWdlcwovQ291bnQgMQovS2lkcyBbIDQgMCBSIF0KPj4KZW5kb2JqCjMgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1BhZ2VzIDIgMCBSCj4+CmVuZG9iago0IDAgb2JqCjw8Ci9UeXBlIC9QYWdlCi9SZXNvdXJjZXMgPDwKPj4KL01lZGlhQm94IFsgMC4wIDAuMCAzMDAgNDAwIF0KL1BhcmVudCAyIDAgUgo+PgplbmRvYmoKNSAwIG9iago8PAovViAyCi9SIDMKL0xlbmd0aCAxMjgKL1AgNDI5NDk2NzI5MgovRmlsdGVyIC9TdGFuZGFyZAovTyA8M2FiYzVmMzgxNzFkZmNmYjE0ZWVkMjI3ZGMyMjcwMzZhZDJhMWZkNjI1MmU5ZWRkM2FiNjAwNjZmMWYzZjRlZT4KL1UgPDkwZWRhNWE0NGQ0MzFiZjQ4Y2Y5NGZiMWNjZmQ2NWU4MjhiZjRlNWU0ZTc1OGE0MTY0MDA0ZTU2ZmZmYTAxMDg+Cj4+CmVuZG9iagp4cmVmCjAgNgowMDAwMDAwMDAwIDY1NTM1IGYgCjAwMDAwMDAwMTUgMDAwMDAgbiAKMDAwMDAwMDA1OSAwMDAwMCBuIAowMDAwMDAwMTE4IDAwMDAwIG4gCjAwMDAwMDAxNjcgMDAwMDAgbiAKMDAwMDAwMDI2MSAwMDAwMCBuIAp0cmFpbGVyCjw8Ci9TaXplIDYKL1Jvb3QgMyAwIFIKL0luZm8gMSAwIFIKL0lEIFsgPDM2MzczMjY0MzYzODM0MzczMTM4NjM2MjMwMzE2NDMzMzgzNTYyNjUzNjMyNjYzMTMwNjIzMjMwMzY2NjM3Mzk+IDwzNjM3MzI2NDM2MzgzNDM3MzEzODYzNjIzMDMxNjQzMzM4MzU2MjY1MzYzMjY2MzEzMDYyMzIzMDM2NjYzNzM5PiBdCi9FbmNyeXB0IDUgMCBSCj4+CnN0YXJ0eHJlZgo0NzYKJSVFT0YK"
    }
}

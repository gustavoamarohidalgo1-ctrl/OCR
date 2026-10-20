package com.facturaclara.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExporterInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun invoice(text: String, blocked: Boolean = false) = Invoice(
        id = "pdf-exporter-test", name = "Factura de prueba.png", createdAt = 0L,
        sourcePath = "", mime = "image/png", status = "ready",
        pages = listOf(ScanPage(index = 1, imagePath = "", text = text,
            blockers = if (blocked) listOf("Se necesita una nueva captura.") else emptyList()))
    )

    @Test fun longTranscriptionCreatesReadableAdditionalPages() {
        val destination = File.createTempFile("export-test-", ".pdf", context.cacheDir)
        try {
            PdfExporter(context).write(invoice((1..180).joinToString("\n") { "Artículo $it  Cantidad 1  Total 123,45" }), Uri.fromFile(destination))
            ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue(renderer.pageCount >= 4)
                    renderer.openPage(0).use { page ->
                        assertEquals(595, page.width)
                        assertEquals(842, page.height)
                    }
                }
            }
        } finally {
            destination.delete()
            File(context.filesDir, "documents/pdf-exporter-test").deleteRecursively()
        }
    }

    @Test fun blockedOrEmptyPagesDoNotTouchDestination() {
        val destination = File.createTempFile("export-validation-", ".pdf", context.cacheDir)
        try {
            for (invalid in listOf(invoice("Total 10", blocked = true), invoice("  ")) ) {
                destination.writeText("contenido existente")
                var rejected = false
                try {
                    PdfExporter(context).write(invalid, Uri.fromFile(destination))
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue(rejected)
                assertEquals("contenido existente", destination.readText())
            }
        } finally {
            destination.delete()
        }
    }

    @Test fun generationPublishesOnlyCompletePdfAndKeepsPreviousOnValidationFailure() {
        val document = invoice("Descripción: café, niño\nTotal: 123,45").apply { status = "processing" }
        val folder = File(context.filesDir, "documents/${document.id}")
        try {
            val pdf = PdfExporter(context).generate(document)
            assertEquals("result.pdf", pdf.name)
            assertTrue(pdf.readBytes().take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("%PDF-"))
            val bytes = pdf.readBytes()
            try {
                PdfExporter(context).generate(invoice("", blocked = true))
            } catch (_: IllegalArgumentException) {
                // Expected: validation precedes all file replacement.
            }
            assertTrue(bytes.contentEquals(pdf.readBytes()))
            assertTrue(folder.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun anExistingPdfNeverBypassesNewBlockers() {
        val document = invoice("Total: 123,45")
        val folder = File(context.filesDir, "documents/${document.id}")
        val destination = File.createTempFile("export-blocked-", ".pdf", context.cacheDir)
        try {
            document.pdfPath = PdfExporter(context).generate(document).absolutePath
            document.pages = listOf(ScanPage(index = 0, imagePath = "", text = "Total: 128,45",
                blockers = listOf("Los importes de las lecturas no coinciden.")))
            destination.writeText("sin modificar")
            var rejected = false
            try {
                PdfExporter(context).write(document, Uri.fromFile(destination))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals("sin modificar", destination.readText())
        } finally {
            folder.deleteRecursively()
            destination.delete()
        }
    }

    @Test fun incompleteJobsCannotExportEvenWithCleanPartialPages() {
        val destination = File.createTempFile("export-incomplete-", ".pdf", context.cacheDir)
        try {
            for (status in listOf("queued", "processing", "failed", "needs_capture")) {
                val document = invoice("Página parcial sin alertas").apply { this.status = status }
                destination.writeText("sin modificar")
                var rejected = false
                try {
                    PdfExporter(context).write(document, Uri.fromFile(destination))
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue("No debe exportar el estado $status", rejected)
                assertEquals("sin modificar", destination.readText())
            }
        } finally {
            destination.delete()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun generatedPdfContainsSelectableAccentedTextAndNoScannedImages() {
        val document = invoice("Descripción: café, niño, Perú\nIGV: 18,00\nTOTAL: 118,00")
        val folder = File(context.filesDir, "documents/${document.id}")
        try {
            val pdf = PdfExporter(context).generate(document)
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val text = page.textContents.joinToString("\n") { it.text }
                        assertTrue(text.contains("Descripción: café, niño, Perú"))
                        assertTrue(text.contains("118,00"))
                        assertTrue(page.imageContents.isEmpty())
                    }
                }
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}

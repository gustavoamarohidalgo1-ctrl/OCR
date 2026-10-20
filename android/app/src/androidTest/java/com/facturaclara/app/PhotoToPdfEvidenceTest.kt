package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * An inspectable pixels -> import -> production worker -> PDF control.
 *
 * This is deliberately a clean, synthetic raster, not a camera photograph or a
 * claim about real-receipt accuracy. The reference text is used only to paint
 * the input image and, after processing, to independently score the OCR output.
 * No reference text or reference geometry is supplied to production code.
 */
@RunWith(AndroidJUnit4::class)
class PhotoToPdfEvidenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = Repository(context)

    @Test
    fun originalRasterWithMergedMerchantWordIsRejectedWithoutPdf() {
        checkFixture("original-word-boundary-regression", wordSpacing = 0f, expectsExact = false)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun rasterWithExplicitlyWiderWordSpacesProducesExactControlTextAndPdf() {
        checkFixture("wide-word-space-control", wordSpacing = 8f, expectsExact = true)
    }

    private fun checkFixture(fixture: String, wordSpacing: Float, expectsExact: Boolean) {
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

        val evidence = File(context.filesDir, "photo-to-pdf-evidence/$fixture").apply { mkdirs() }
        val source = File(evidence, "source.png")
        val exportedPdf = File(evidence, "result.pdf")
        // Do not leave an older successful PDF beside a failed new run.
        exportedPdf.delete()
        File(evidence, "recognized.txt").delete()
        File(evidence, "outcome.json").delete()
        File(evidence, "expected.txt").writeText(expected, Charsets.UTF_8)
        writeSourceImage(source, expected, wordSpacing)

        // The same import entry point used by the application copies only image
        // bytes and schedules InvoiceWorker. It receives no expected text.
        val imported = repository.importUri(Uri.fromFile(source))
        val result = awaitDocument(imported.id)
        val recognized = result.pages.joinToString("\n\n") { it.text }
        File(evidence, "recognized.txt").writeText(recognized, Charsets.UTF_8)

        // Independent reference comparisons: do not reuse production consensus
        // logic, since two agreeing readings can still be identically wrong.
        val charactersMatch = characters(expected) == characters(recognized)
        val tokenSequenceMatches = tokens(expected) == tokens(recognized)
        val numericTokensMatch = numericTokens(expected) == numericTokens(recognized)
        val importedBytesMatch = sha256(source) == sha256(File(result.sourcePath))
        var pageCount = 0
        result.pdfPath?.let { path ->
            val generated = File(path)
            if (generated.isFile) {
                generated.copyTo(exportedPdf, overwrite = true)
                PdfRenderer(ParcelFileDescriptor.open(exportedPdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    pageCount = pdf.pageCount
                }
            }
        }

        File(evidence, "outcome.json").writeText(JSONObject().apply {
            put("fixture", fixture)
            put("is_synthetic", true)
            put("is_camera_photo", false)
            put("tuned_control", wordSpacing != 0f)
            put("additional_word_spacing_pixels", wordSpacing.toDouble())
            put("expected_outcome", if (expectsExact) "exact_control" else "reject_merged_merchant_word")
            put("source_kind", "PNG rendered from fictional reference text")
            put("pipeline", "Repository.importUri -> WorkManager -> InvoiceWorker -> bundled OCR -> PdfExporter")
            put("expected_text_supplied_to_production", false)
            put("is_design_example", result.isDesignExample)
            put("invoice_id", result.id)
            put("status", result.status)
            put("error", result.error ?: JSONObject.NULL)
            put("source_sha256", sha256(source))
            put("imported_bytes_match", importedBytesMatch)
            put("character_comparison_ignores_whitespace_and_normalizes_unicode_nfc", true)
            put("token_comparison_preserves_word_and_code_boundaries", true)
            put("ordered_characters_match", charactersMatch)
            put("ordered_tokens_match", tokenSequenceMatches)
            put("ordered_numeric_tokens_match", numericTokensMatch)
            put("expected_character_count_without_whitespace", characters(expected).length)
            put("recognized_character_count_without_whitespace", characters(recognized).length)
            put("pdf_pages", pageCount)
            put("pdf_created_by_production_worker", exportedPdf.isFile)
            put("warnings", JSONArray(result.pages.flatMap { it.warnings }))
            put("blockers", JSONArray(result.pages.flatMap { it.blockers }))
            put("scope", if (expectsExact) {
                "Synthetic control with word spaces widened after an original fixture failed. " +
                    "Does not establish accuracy for camera photos or real invoices."
            } else {
                "Preserved original synthetic failure: letters-only comparison hid a merged merchant word. " +
                    "Expected rejection is not a successful transcription."
            })
        }.toString(2), Charsets.UTF_8)

        val diagnostic = "status=${result.status}, error=${result.error}, " +
            "blockers=${result.pages.flatMap { it.blockers }}\nOCR:\n$recognized"
        assertEquals(diagnostic, 1, result.pages.size)
        assertFalse("The OCR control must not use the direct design-preview path.", result.isDesignExample)
        assertTrue("The imported bytes must be the independently rendered PNG.", importedBytesMatch)
        assertTrue("Letters, digits and punctuation must match in order.\n$diagnostic", charactersMatch)
        if (expectsExact) {
            assertTrue("Numeric tokens must match without merging distinct quantities.\n$diagnostic", numericTokensMatch)
            assertEquals(diagnostic, "ready", result.status)
            assertTrue("Words and punctuation must match as ordered tokens.\n$diagnostic", tokenSequenceMatches)
            assertTrue(diagnostic, result.pages.all { it.blockers.isEmpty() })
            assertTrue("The PDF must be the worker's real output.", exportedPdf.isFile && exportedPdf.length() > 0)
            assertEquals("The short control must fit on one page.", 1, pageCount)
        } else {
            assertEquals(diagnostic, "review", result.status)
            assertFalse("The preserved original fixture loses a word boundary.\n$diagnostic", tokenSequenceMatches)
            assertTrue(diagnostic, result.pages.any { it.blockers.isNotEmpty() })
            assertTrue("Unconfirmed text is exported for review.", exportedPdf.isFile && exportedPdf.length() > 0)
            assertEquals(1, pageCount)
        }
    }

    private fun writeSourceImage(destination: File, text: String, extraWordSpacing: Float) {
        val bitmap = Bitmap.createBitmap(1600, 2100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 44f
                if (Build.VERSION.SDK_INT >= 29 && extraWordSpacing != 0f) {
                    wordSpacing = extraWordSpacing
                }
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            text.lines().forEachIndexed { index, line ->
                canvas.drawText(line, 100f, 150f + index * 105f, paint)
            }
            destination.outputStream().use { stream ->
                assertTrue("Could not write source PNG.", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitDocument(id: String): Invoice {
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val invoice = repository.get(id) ?: error("The imported document disappeared.")
            if (invoice.status in setOf("ready", "review", "needs_recapture", "failed")) return invoice
            Thread.sleep(150)
        }
        throw AssertionError("Processing timed out: ${repository.get(id)?.status}")
    }

    private fun normalized(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun characters(text: String): String = normalized(text)
        .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    private fun tokens(text: String): List<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{Z}]")
            .findAll(normalized(text)).map { it.value }.toList()

    private fun numericTokens(text: String): List<String> =
        Regex("\\d+(?:[.,/\\-]\\d+)*").findAll(normalized(text)).map { it.value }.toList()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

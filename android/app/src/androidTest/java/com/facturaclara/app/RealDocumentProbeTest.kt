package com.facturaclara.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Diagnostic only: reaching ready is not a measurement of transcription accuracy. */
@RunWith(AndroidJUnit4::class)
class RealDocumentProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = Repository(context)

    @Test fun publishedReceiptsReachExplicitFinalStateThroughRealImportAndWorker() {
        val samples = listOf(
            Sample("sol-p106", "boleta-real-sol-unaj-p106.pdf", 106),
            Sample("comercial-p108", "boleta-real-comercial-unaj-p108.pdf", 108),
        )
        samples.forEach { sample ->
            val imported = File(context.cacheDir, sample.asset)
            try {
                instrumentation.context.assets.open("real-document-probes/${sample.asset}").use { input ->
                    imported.outputStream().use { output -> input.copyTo(output) }
                }
                // This is the production entry point. It copies the original,
                // persists metadata and enqueues the production WorkManager job.
                val original = repository.importUri(Uri.fromFile(imported))
                val result = awaitDocument(original.id)
                saveProbe(sample, result)
                assertTrue("${sample.alias}: el procesamiento debe terminar; estado=${result.status}",
                    result.status in FINAL_STATES)
                assertEquals("${sample.alias}: la página de origen debe conservarse", 1, result.pages.size)
                if (result.status == "ready") {
                    assertTrue("${sample.alias}: ready no permite bloqueos", result.pages.all { it.blockers.isEmpty() })
                    assertTrue("${sample.alias}: ready requiere texto", result.pages.all { it.text.isNotBlank() })
                    assertNotNull("${sample.alias}: falta PDF generado", result.pdfPath)
                    assertTrue("${sample.alias}: PDF generado vacío", File(result.pdfPath!!).length() > 0L)
                } else if (result.status == "review") {
                    assertTrue("${sample.alias}: review requiere texto", result.pages.all { it.text.isNotBlank() })
                    assertTrue("${sample.alias}: review requiere un motivo", result.pages.any { it.blockers.isNotEmpty() })
                    assertTrue("${sample.alias}: falta PDF para revisar", result.pdfPath?.let { File(it).length() > 0L } == true)
                } else {
                    assertNull("${sample.alias}: un rechazo no puede publicar PDF", result.pdfPath)
                    assertTrue("${sample.alias}: el rechazo debe tener motivo explícito",
                        !result.error.isNullOrBlank() || result.pages.any { it.blockers.isNotEmpty() })
                }
            } finally {
                imported.delete()
            }
        }
    }

    private fun awaitDocument(id: String): Invoice {
        val deadline = SystemClock.elapsedRealtime() + 180_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val invoice = repository.get(id) ?: throw AssertionError("El documento importado desapareció.")
            if (invoice.status in FINAL_STATES) return invoice
            Thread.sleep(200L)
        }
        // Persist the nonterminal snapshot before the assertion in the caller.
        return repository.get(id) ?: throw AssertionError("El documento importado desapareció.")
    }

    private fun saveProbe(sample: Sample, invoice: Invoice) {
        val directory = File(context.filesDir, "real-document-probes/${sample.alias}")
        directory.deleteRecursively()
        check(directory.mkdirs()) { "No se pudo crear la carpeta de diagnóstico." }
        val documentJson = File(repository.documentDir(invoice.id), "document.json")
        val report = JSONObject().apply {
            put("sample", sample.alias)
            put("source_url", SOURCE_URL)
            put("source_page", sample.page)
            put("source_page_count", 1)
            put("final_state_reached", invoice.status in FINAL_STATES)
            put("accuracy_claim", JSONObject.NULL)
            put("note", "Prueba diagnóstica de procesamiento. No existe una referencia humana validada ni una medida de exactitud.")
            put("document", JSONObject(documentJson.readText(Charsets.UTF_8)))
            put("blockers", JSONArray(invoice.pages.flatMap { it.blockers }))
        }
        File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
        File(directory, "transcription.txt").writeText(invoice.pages.joinToString("\n\n") { it.text }, Charsets.UTF_8)
        invoice.pages.forEach { page ->
            File(page.imagePath).takeIf { it.isFile }?.copyTo(File(directory, "page-${page.index + 1}.png"), overwrite = true)
        }
        if (invoice.status == "ready" || invoice.status == "review") {
            invoice.pdfPath?.let(::File)?.takeIf { it.isFile }?.copyTo(File(directory, "result.pdf"), overwrite = true)
        }
    }

    private data class Sample(val alias: String, val asset: String, val page: Int)

    companion object {
        private val FINAL_STATES = setOf("ready", "review", "needs_recapture", "failed")
        private const val SOURCE_URL = "https://portal.unaj.edu.pe/sites/default/files/proyeccion-social-informes2025/Wi%C3%B1ariypaq%20hukllachasqa.pdf"
    }
}

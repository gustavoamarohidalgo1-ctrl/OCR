package com.facturaclara.app

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Diagnostic camera-photo batch using the ordinary import and production worker.
 * No reference transcription, manual corrections, synthetic page, or direct PDF
 * export is supplied. A ready document is NOT proof of transcription accuracy.
 * Assets live only in androidTest and never ship in the production application.
 */
@RunWith(AndroidJUnit4::class)
class RealReceiptBatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = Repository(context)

    @Test
    fun originalCameraPhotosUseRealImportAndWorkerWithoutTextCorrections() {
        val label = InstrumentationRegistry.getArguments().getString("runLabel", "baseline")
            .replace(Regex("[^a-zA-Z0-9_-]"), "-").take(40)
        val runId = "$label-${System.currentTimeMillis()}"
        val evidence = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "real-receipts/$runId").apply { check(mkdirs()) }
        val manifest = JSONObject(instrumentation.context.assets.open("$ASSET_DIRECTORY/sources.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        File(evidence, "sources.json").writeText(manifest.toString(2), Charsets.UTF_8)
        val availableSamples = manifest.getJSONArray("samples")
        val selection = InstrumentationRegistry.getArguments().getString("sampleSelection")
            ?.split(',')?.filter { it.isNotBlank() }?.toSet()
        val samples = JSONArray((0 until availableSamples.length()).map { availableSamples.getJSONObject(it) }
            .filter { selection == null || it.getString("filename").take(2) in selection })
        check(samples.length() > 0)
        val outcomes = JSONArray()
        val violations = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appApk = File(context.applicationInfo.sourceDir)
        val appSha256 = sha256(appApk)

        fun saveSummary() {
            File(evidence, "summary.json").writeText(JSONObject().apply {
                put("run_label", label)
                put("run_id", runId)
                put("started_at_epoch_ms", startedAt)
                put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                put("application_version", packageInfo.versionName)
                put("installed_apk_sha256", appSha256)
                put("evidence_directory", evidence.absolutePath)
                put("pipeline", "Repository.importUri -> WorkManager -> InvoiceWorker -> bundled OCR -> PdfExporter when accepted")
                put("images_edited", false)
                put("manual_ocr_corrections", false)
                put("reference_text_supplied_to_production", false)
                put("accuracy_claim", JSONObject.NULL)
                put("note", "Diagnostic processing results; no human ground truth is supplied. Ready does not establish perfect transcription.")
                put("expected_sample_count", samples.length())
                put("completed_sample_count", outcomes.length())
                put("invariant_violations", JSONArray(violations))
                put("samples", outcomes)
            }.toString(2), Charsets.UTF_8)
        }

        saveSummary()
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            val filename = sample.getString("filename")
            val sampleDirectory = File(evidence, filename.substringBeforeLast('.')).apply { check(mkdirs()) }
            val source = File(sampleDirectory, "source.${filename.substringAfterLast('.')}")
            instrumentation.context.assets.open("$ASSET_DIRECTORY/$filename").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            // This temporary filename clearly identifies added QA documents in
            // the app without changing any imported image bytes or existing data.
            val importFile = File(context.cacheDir, "QA-$runId-$filename")
            val sampleStarted = SystemClock.elapsedRealtime()
            val outcome = JSONObject().apply {
                put("filename", filename)
                put("source_sha256", sha256(source))
                put("expected_original_sha256", sample.getString("sha256"))
                put("source_bytes", source.length())
            }
            try {
                source.copyTo(importFile, overwrite = false)
                val original = repository.importUri(Uri.fromFile(importFile))
                val result = awaitDocument(original.id)
                val importedSource = File(result.sourcePath)
                val importedHash = sha256(importedSource)
                val sourceMatches = sha256(source) == sample.getString("sha256") &&
                    importedHash == sample.getString("sha256")
                val documentFile = File(repository.documentDir(result.id), "document.json")
                documentFile.copyTo(File(sampleDirectory, "document.json"), overwrite = false)
                File(sampleDirectory, "recognized.txt").writeText(
                    result.pages.joinToString("\n\n") { it.text }, Charsets.UTF_8)
                result.pages.forEach { page ->
                    File(page.imagePath).takeIf { it.isFile }?.copyTo(
                        File(sampleDirectory, "preview-page-${page.index + 1}.png"), overwrite = false)
                    File(repository.documentDir(result.id), "verification-%03d.json".format(page.index + 1))
                        .takeIf { it.isFile }?.copyTo(File(sampleDirectory, "verification-${page.index + 1}.json"), overwrite = false)
                    for (prefix in listOf("page-verification", "fallback-evidence")) {
                        File(repository.documentDir(result.id), "$prefix-%03d.json".format(page.index + 1))
                            .takeIf { it.isFile }?.copyTo(File(sampleDirectory, "$prefix-${page.index + 1}.json"), overwrite = false)
                    }
                    File(repository.documentDir(result.id), "verification-error-%03d.json".format(page.index + 1))
                        .takeIf { it.isFile }?.copyTo(File(sampleDirectory, "verification-error-${page.index + 1}.json"), overwrite = false)
                }
                var pdfPages = 0
                var pdfHash: String? = null
                var pdfBytes = 0L
                result.pdfPath?.let(::File)?.takeIf { it.isFile }?.let { generated ->
                    val exported = File(sampleDirectory, "result.pdf")
                    generated.copyTo(exported, overwrite = false)
                    pdfHash = sha256(exported)
                    pdfBytes = exported.length()
                    PdfRenderer(ParcelFileDescriptor.open(exported, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                        pdfPages = it.pageCount
                    }
                }
                outcome.put("invoice_id", result.id)
                outcome.put("invoice_name", result.name)
                outcome.put("status", result.status)
                outcome.put("error", result.error ?: JSONObject.NULL)
                outcome.put("final_state_reached", result.status in FINAL_STATES)
                outcome.put("imported_sha256", importedHash)
                outcome.put("original_bytes_preserved", sourceMatches)
                outcome.put("ocr_page_count", result.pages.size)
                outcome.put("ocr_character_count", result.pages.sumOf { it.text.length })
                outcome.put("geometry_line_count", result.pages.sumOf { it.lines.size })
                outcome.put("doubtful_line_count", result.pages.sumOf { page -> page.lines.count { it.doubtful } })
                outcome.put("warnings", JSONArray(result.pages.flatMap { it.warnings }))
                outcome.put("blockers", JSONArray(result.pages.flatMap { it.blockers }))
                outcome.put("is_design_example", result.isDesignExample)
                outcome.put("pdf_created_by_production_worker", pdfBytes > 0)
                outcome.put("pdf_bytes", pdfBytes)
                outcome.put("pdf_pages", pdfPages)
                outcome.put("pdf_sha256", pdfHash ?: JSONObject.NULL)
                if (!sourceMatches) violations += "$filename: original image bytes changed"
                if (result.status !in FINAL_STATES) violations += "$filename: processing timed out (${result.status})"
                if (result.isDesignExample) violations += "$filename: used design-example path"
                if (result.status == "ready") {
                    if (result.pages.any { it.blockers.isNotEmpty() || it.text.isBlank() })
                        violations += "$filename: ready document has blocked or blank page"
                    if (pdfBytes <= 0 || pdfPages <= 0) violations += "$filename: ready document lacks readable PDF"
                } else if (result.status == "review") {
                    if (result.pages.any { it.text.isBlank() }) violations += "$filename: review document has blank page"
                    if (result.pages.none { it.blockers.isNotEmpty() }) violations += "$filename: review document has no unconfirmed page"
                    if (pdfBytes <= 0 || pdfPages <= 0) violations += "$filename: review document lacks readable PDF"
                } else if (pdfBytes > 0 || result.pdfPath != null) {
                    violations += "$filename: rejected document unexpectedly publishes PDF"
                }
            } catch (error: Exception) {
                outcome.put("diagnostic_error", error.toString())
                violations += "$filename: diagnostic exception ${error.message}"
            } finally {
                importFile.delete()
                outcome.put("elapsed_ms", SystemClock.elapsedRealtime() - sampleStarted)
                File(sampleDirectory, "outcome.json").writeText(outcome.toString(2), Charsets.UTF_8)
                outcomes.put(outcome)
                saveSummary()
                val progress = "${index + 1}/${samples.length()} $filename: ${outcome.optString("status", "diagnostic_error")} (${outcome.getLong("elapsed_ms")} ms)"
                Log.i("RealReceiptBatch", progress)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$progress\n") })
            }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nBATCH_EVIDENCE=${evidence.absolutePath}\n") })
        assertTrue("Diagnostic invariants failed: ${violations.joinToString("; ")}", violations.isEmpty())
    }

    private fun awaitDocument(id: String): Invoice {
        // Diagnostic batch: unconfirmed pages also run the engine vote, which takes longer.
        val deadline = SystemClock.elapsedRealtime() + 600_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val invoice = repository.get(id) ?: error("Imported document disappeared: $id")
            if (invoice.status in FINAL_STATES) return invoice
            Thread.sleep(200L)
        }
        return repository.get(id) ?: error("Imported document disappeared: $id")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val ASSET_DIRECTORY = "real-receipt-photos"
        private val FINAL_STATES = setOf("ready", "review", "needs_recapture", "failed")
    }
}

package com.facturaclara.app

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic: imports every image of an app-specific folder through the same path as
 * "Subir foto" (Repository.importUri, WorkManager, InvoiceWorker) and keeps evidence.
 * The documents stay in the tray for the user. No reference text is involved.
 * Arguments: folder (under the app's external files dir), prefix (document name prefix).
 */
@RunWith(AndroidJUnit4::class)
class ImportFolderProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = Repository(context)

    @Test fun importFolderLikeTheUploadButton() {
        val arguments = InstrumentationRegistry.getArguments()
        val folderName = arguments.getString("folder")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
        assumeTrue("Pass -e folder <name>", folderName != null)
        val prefix = arguments.getString("prefix", "").replace(Regex("[^A-Za-z0-9_-]"), "").take(20)
        val root = context.getExternalFilesDir(null)!!
        val folder = File(root, folderName!!)
        val images = folder.listFiles { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "pdf") }
            .orEmpty().sortedBy { it.name }
        check(images.isNotEmpty()) { "No images in $folder" }
        val evidence = File(root, "import-runs/$folderName-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val outcomes = JSONArray()
        for (image in images) {
            val started = SystemClock.elapsedRealtime()
            val named = File(context.cacheDir, "$prefix${image.name}")
            image.copyTo(named, overwrite = true)
            val outcome = JSONObject().put("file", image.name)
            try {
                val imported = repository.importUri(Uri.fromFile(named))
                val result = await(imported.id)
                val sample = File(evidence, image.nameWithoutExtension).apply { mkdirs() }
                File(repository.documentDir(result.id), "document.json").copyTo(File(sample, "document.json"), overwrite = true)
                File(sample, "recognized.txt").writeText(result.pages.joinToString("\n\n") { it.text })
                result.pdfPath?.let(::File)?.takeIf { it.isFile }?.copyTo(File(sample, "result.pdf"), overwrite = true)
                outcome.put("status", result.status).put("error", result.error ?: JSONObject.NULL)
                    .put("characters", result.pages.sumOf { it.text.length })
                    .put("fragments", result.pages.sumOf { it.lines.size })
                    .put("doubtful", result.pages.sumOf { page -> page.lines.count { it.doubtful } })
                    .put("pdf", result.pdfPath != null)
                    .put("warnings", JSONArray(result.pages.flatMap { it.warnings }))
                    .put("blockers", JSONArray(result.pages.flatMap { it.blockers }))
            } catch (error: Exception) {
                outcome.put("error", error.toString())
            } finally {
                named.delete()
                outcome.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                outcomes.put(outcome)
                File(evidence, "summary.json").writeText(JSONObject().put("outcomes", outcomes).toString(2))
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\n${image.name}: ${outcome.optString("status", "error")} (${outcome.getLong("elapsed_ms")} ms)\n")
                })
            }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\nEVIDENCE=${evidence.absolutePath}\n") })
    }

    private fun await(id: String): Invoice {
        val deadline = SystemClock.elapsedRealtime() + 600_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val invoice = repository.get(id) ?: error("Imported document disappeared: $id")
            if (invoice.status in setOf("ready", "review", "needs_recapture", "failed")) return invoice
            Thread.sleep(250L)
        }
        return repository.get(id) ?: error("Imported document disappeared: $id")
    }
}

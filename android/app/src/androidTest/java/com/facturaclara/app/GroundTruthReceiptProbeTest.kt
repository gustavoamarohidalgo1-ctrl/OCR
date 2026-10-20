package com.facturaclara.app

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Records real raster -> production worker output; successful execution is not an accuracy claim. */
@RunWith(AndroidJUnit4::class)
class GroundTruthReceiptProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = Repository(context)

    @Test fun originalPublishedReceiptIsMeasuredThroughRealImport() {
        val imported = File(context.cacheDir, "sol-p106-original.png")
        val directory = File(context.filesDir, "accuracy-probes/sol-p106")
        directory.deleteRecursively()
        check(directory.mkdirs())
        try {
            instrumentation.context.assets.open("accuracy-probes/sol-p106-original.png").use { input ->
                imported.outputStream().use { output -> input.copyTo(output) }
            }
            imported.copyTo(File(directory, "original.png"), overwrite = true)
            instrumentation.context.assets.open("accuracy-probes/sol-p106-reference.json").use { input ->
                File(directory, "reference.json").outputStream().use { output -> input.copyTo(output) }
            }
            val initial = repository.importUri(Uri.fromFile(imported))
            val result = awaitDocument(initial.id)
            val json = JSONObject(File(repository.documentDir(result.id), "document.json").readText(Charsets.UTF_8))
            val report = JSONObject().apply {
                put("sample", "sol-p106-original")
                put("reference_file", "reference.json")
                put("input_file", "original.png")
                put("input_width", 729)
                put("input_height", 543)
                put("accuracy_claim", JSONObject.NULL)
                put("note", "Diagnóstico real. Medir contra la referencia visual; una prueba completada no significa transcripción perfecta.")
                put("document", json)
            }
            File(directory, "transcription.txt").writeText(result.pages.joinToString("\n\n") { it.text }, Charsets.UTF_8)
            result.pages.forEach { page ->
                File(page.imagePath).copyTo(File(directory, "processed-page-${page.index + 1}.png"), overwrite = true)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.imagePath, options)
                report.put("processed_width", options.outWidth)
                report.put("processed_height", options.outHeight)
            }
            File(directory, "result.json").writeText(report.toString(2), Charsets.UTF_8)
            assertTrue("Processing did not finish: ${result.status}", result.status in FINAL_STATES)
            assertFalse("Public receipt must use production OCR", result.isDesignExample)
            assertEquals(1, result.pages.size)
            if (result.status == "ready") {
                assertTrue(result.pages.all { it.blockers.isEmpty() && it.text.isNotBlank() })
                assertTrue(result.pdfPath?.let { File(it).isFile } == true)
                File(result.pdfPath!!).copyTo(File(directory, "result.pdf"), overwrite = true)
            } else if (result.status == "review") {
                assertTrue(result.pages.all { it.text.isNotBlank() } && result.pages.any { it.blockers.isNotEmpty() })
                assertTrue(result.pdfPath?.let { File(it).isFile } == true)
                File(result.pdfPath!!).copyTo(File(directory, "result.pdf"), overwrite = true)
            } else {
                assertNull("Rejected receipt cannot publish a PDF", result.pdfPath)
                assertTrue(!result.error.isNullOrBlank() || result.pages.any { it.blockers.isNotEmpty() })
            }
        } finally {
            imported.delete()
        }
    }

    private fun awaitDocument(id: String): Invoice {
        val deadline = SystemClock.elapsedRealtime() + 180_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = repository.get(id) ?: error("Imported receipt disappeared")
            if (result.status in FINAL_STATES) return result
            Thread.sleep(200L)
        }
        return repository.get(id) ?: error("Imported receipt disappeared")
    }

    companion object {
        private val FINAL_STATES = setOf("ready", "review", "needs_recapture", "failed")
    }
}

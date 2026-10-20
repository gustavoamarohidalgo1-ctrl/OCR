package com.facturaclara.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OcrGeometryPersistenceTest {
    @Test
    fun normalizedGeometryAndCompleteTextSurviveSavingAndReloading() {
        withDocument { repository, invoice, _ ->
            val lines = listOf(OcrLine("Papel A4", .1f, .2f, .5f, .23f, .01f, .94f),
                OcrLine("2", .6f, .2f, .65f, .23f, .01f, .88f),
                OcrLine("60.00", .8f, .2f, .95f, .23f, .01f, .91f))
            invoice.pages = listOf(ScanPage(0, "page-001.png", "Papel A4   2   60.00", lines = lines))
            repository.save(invoice)
            val reloaded = repository.get(invoice.id)!!
            assertEquals(invoice.pages, reloaded.pages)
            assertEquals(lines, OcrLayout.rows(reloaded.pages.single().lines).single())
        }
    }

    @Test
    fun olderJsonWithoutLinesRetainsTheOriginalTranscription() {
        withDocument { repository, invoice, folder ->
            invoice.pages = listOf(ScanPage(0, "page-001.png", "Texto completo\nTOTAL 118.00"))
            repository.save(invoice)
            val file = File(folder, "document.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("pages").getJSONObject(0).remove("lines")
            file.writeText(json.toString())
            val page = repository.get(invoice.id)!!.pages.single()
            assertEquals(invoice.pages.single().text, page.text)
            assertTrue(page.lines.isEmpty())
        }
    }

    @Test
    fun invalidStoredGeometryFallsBackToCompleteTextInsteadOfPartialCells() {
        withDocument { repository, invoice, folder ->
            invoice.pages = listOf(ScanPage(0, "page-001.png", "Subtotal 100.00\nTOTAL 118.00",
                lines = listOf(OcrLine("TOTAL 118.00", .1f, .2f, .9f, .25f))))
            repository.save(invoice)
            val file = File(folder, "document.json")
            val json = JSONObject(file.readText())
            json.getJSONArray("pages").getJSONObject(0).getJSONArray("lines").getJSONObject(0).put("left", -1)
            file.writeText(json.toString())
            val page = repository.get(invoice.id)!!.pages.single()
            assertEquals(invoice.pages.single().text, page.text)
            assertTrue(page.lines.isEmpty())
        }
    }

    private fun withDocument(block: (Repository, Invoice, File) -> Unit) {
        val repository = Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        val folder = repository.documentDir(id)
        try {
            block(repository, Invoice(id, "Prueba de geometría", 1L, "original.png", "image/png",
                status = "needs_recapture"), folder)
        } finally { folder.deleteRecursively() }
    }
}

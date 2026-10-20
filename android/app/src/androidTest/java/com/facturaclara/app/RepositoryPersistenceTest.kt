package com.facturaclara.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
    @Test fun interruptedWriteWithOnlyBackupRestoresOriginalDocument() {
        val repository = Repository(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        val folder = repository.documentDir(id)
        try {
            val document = Invoice(id, "Factura preservada", 1L, "original.pdf", "application/pdf", status = "needs_recapture")
            repository.save(document)
            val original = File(folder, "document.json")
            assertTrue(original.renameTo(File(folder, "document.json.bak")))
            assertEquals(document.name, repository.get(id)?.name)
            assertTrue(original.isFile)
            assertTrue(repository.list().any { it.id == id })
        } finally {
            folder.deleteRecursively()
        }
    }
}

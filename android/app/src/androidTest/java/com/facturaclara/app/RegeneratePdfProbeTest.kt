package com.facturaclara.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Diagnostic: regenerates the PDF of already processed documents whose name starts with
 * a prefix (as "Guardar PDF" does), without reading them again, and copies each PDF to
 * an evidence folder. Argument: prefix.
 */
@RunWith(AndroidJUnit4::class)
class RegeneratePdfProbeTest {
    @Test fun regenerateWithCurrentLayout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = InstrumentationRegistry.getArguments().getString("prefix")
        assumeTrue("Pass -e prefix <name prefix>", !prefix.isNullOrBlank())
        val evidence = File(context.getExternalFilesDir(null), "regenerated/$prefix-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val repository = Repository(context)
        repository.list().filter { it.name.startsWith(prefix!!) && (it.status == "ready" || it.status == "review") }.forEach { invoice ->
            PdfExporter(context).generate(invoice).copyTo(File(evidence, invoice.name.substringBeforeLast('.') + ".pdf"), overwrite = true)
        }
    }
}

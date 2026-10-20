package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LocalRecoveryInstrumentedTest {
    @Test fun independentBundledModelReadsCameraPixelsWithoutChangingTheSource() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("real-receipt-photos/07_luz_azulada.jpg").use { it.readBytes() }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        original.density = Bitmap.DENSITY_NONE
        val before = pixelHash(original)
        try {
            IndependentOcr(instrumentation.targetContext).use { engine ->
                val readings = engine.recognize(original)
                assertEquals(2, readings.size)
                assertTrue(readings.all { words -> words.any { it.text == "Haverhill" && (it.confidence ?: 0f) >= .85f } })
                assertTrue(readings.flatten().all { it.left >= 0 && it.right <= 1 && it.top >= 0 && it.bottom <= 1 })
                assertArrayEquals(before, pixelHash(original))
                engine.cancel()
                try { engine.recognize(original); fail("Cancelled reader must not start another recognition") }
                catch (_: InterruptedException) { /* cancellation acknowledged */ }
            }
        } finally { original.recycle() }
    }

    @Test fun regionalDeskewKeepsSourceCoordinatesAndCapsMemory() {
        val source = Bitmap.createBitmap(2000, 3000, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.WHITE)
        val row = OcrLine("Geometry test", .1f, .3f, .7f, .34f, .04f, .9f)
        val prepared = RegionRaster.prepare(source, listOf(row), 3.0, true, true)
        try {
            assertTrue(prepared.bitmap.width.toLong() * prepared.bitmap.height <= 4_010_000L)
            val forward = android.graphics.Matrix().also { assertTrue(prepared.inverse.invert(it)) }
            val center = floatArrayOf(.4f * source.width, .32f * source.height)
            forward.mapPoints(center)
            val x = center[0] / prepared.bitmap.width
            val y = center[1] / prepared.bitmap.height
            val mapped = RegionRaster.map(OcrLine("Marker", x-.01f, y-.01f, x+.01f, y+.01f), prepared, source.width, source.height)!!
            assertEquals(.4f, (mapped.left + mapped.right) / 2, .0001f)
            assertEquals(.32f, (mapped.top + mapped.bottom) / 2, .0001f)
            assertTrue(abs(mapped.slope-row.slope) < .0001f)
            assertFalse(source.isRecycled)
            assertEquals(android.graphics.Color.WHITE, source.getPixel(800, 960))
        } finally { prepared.bitmap.recycle(); source.recycle() }
    }

    @Test fun cancellingRunningImportPreventsPdfAndDoesNotPoisonNextJob() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = Repository(context)
        val manager = androidx.work.WorkManager.getInstance(context)
        fun load(name: String): Invoice {
            val temporary = java.io.File(context.cacheDir, "QA-cancel-${System.nanoTime()}-$name")
            try {
                instrumentation.context.assets.open("real-receipt-photos/$name").use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                return repository.importUri(android.net.Uri.fromFile(temporary))
            } finally { temporary.delete() }
        }
        fun await(id: String, desired: Set<String>, timeout: Long): Invoice {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            do {
                val current = repository.get(id)!!
                if (current.status in desired) return current
                Thread.sleep(40)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            error("Job did not reach $desired: ${repository.get(id)?.status}")
        }
        val first = load("07_luz_azulada.jpg")
        await(first.id, setOf("processing"), 15_000)
        Thread.sleep(200)
        manager.cancelUniqueWork("invoice-${first.id}").result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        val stopped = await(first.id, setOf("failed"), 10_000)
        assertNull(stopped.pdfPath)
        assertTrue(stopped.error.orEmpty().contains("interrump"))
        val next = load("08_pliegues_y_contraste.jpg")
        val completed = await(next.id, setOf("ready", "review", "failed", "needs_recapture"), 90_000)
        assertTrue("The next job must complete with a PDF: ${completed.status}", completed.status in setOf("ready", "review"))
        assertNotNull(completed.pdfPath)
        // Keep these test-only imports recoverable, without cluttering the user's tray.
        val archive = java.io.File(context.filesDir, "qa-archive/cancellation-${System.currentTimeMillis()}").apply { mkdirs() }
        for (document in listOf(stopped, completed)) {
            assertTrue(repository.documentDir(document.id).renameTo(java.io.File(archive, document.id)))
        }
    }

    private fun pixelHash(bitmap: Bitmap): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array())
    }
}

package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Verifies actual Android codec behavior, including its rounded density scaling. */
@RunWith(AndroidJUnit4::class)
class ImageDecodeSizingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun jpegAndPngKeepMoreDetailWithinTheSamePixelBudget() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            withEncodedImage(2000, 1500, format) { file ->
                val plan = ImageDecodeSizing.plan(2000, 1500, 2_000_000L, 12000)
                decode(file, plan).let { bitmap ->
                    try {
                        assertEquals(plan.width, bitmap.width)
                        assertEquals(plan.height, bitmap.height)
                        assertTrue(bitmap.width.toLong() * bitmap.height in 1_990_000L..2_000_000L)
                        assertTrue(bitmap.allocationByteCount <= 2_000_000L * 4)
                        // Old power-of-two decoding yielded only 1000 x 750.
                        assertTrue(bitmap.width > 1000 && bitmap.height > 750)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test fun oddSampledDimensionsAndSideLimitRemainInsideBudget() {
        withEncodedImage(4001, 1001, Bitmap.CompressFormat.PNG) { file ->
            val plan = ImageDecodeSizing.plan(4001, 1001, 1_000_000L, 1800)
            val bitmap = decode(file, plan)
            try {
                assertTrue(bitmap.width <= 1800 && bitmap.height <= 1800)
                assertTrue(bitmap.width.toLong() * bitmap.height <= 1_000_000L)
                // Android codecs may floor or ceil odd sampled dimensions.
                assertTrue(bitmap.width in (plan.width - 1)..plan.width)
                assertTrue(bitmap.height in (plan.height - 1)..plan.height)
            } finally { bitmap.recycle() }
        }
    }

    private fun decode(file: File, plan: ImageDecodeSizing.Plan): Bitmap =
        requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inScaled = plan.scales
            inDensity = plan.sourceDensity
            inTargetDensity = plan.targetDensity
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))

    private fun withEncodedImage(width: Int, height: Int, format: Bitmap.CompressFormat, block: (File) -> Unit) {
        val file = File.createTempFile("decode-sizing-", ".image", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) }
            } finally { bitmap.recycle() }
            block(file)
        } finally { file.delete() }
    }
}

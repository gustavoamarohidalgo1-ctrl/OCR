package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DocumentBoundaryInstrumentedTest {
    @Test fun outsideProofRequiresSeparationOfTheWholeBoxIncludingUncertainty() {
        val result = DocumentBoundary.Detection(listOf(PointF(.2f, .2f), PointF(.8f, .2f),
            PointF(.8f, .8f), PointF(.2f, .8f)), 20f, 1000, 1000, 1f, 0f)
        fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrLine("", left, top, right, bottom)
        assertTrue(result.definitelyOutside(box(.85f, .4f, .95f, .5f)))
        assertFalse(result.definitelyOutside(box(.80f, .4f, .81f, .5f)))
        assertFalse(result.definitelyOutside(box(.7f, .4f, .95f, .5f)))
        assertFalse(result.definitelyOutside(box(.1f, .1f, .9f, .9f)))
        assertFalse(result.definitelyOutside(box(.3f, .3f, .4f, .4f)))
        assertThrows(IllegalArgumentException::class.java) { box(Float.NaN, .3f, .4f, .4f) }
    }

    @Test fun uniformBackgroundCannotEstablishAPaperBoundary() {
        val bitmap = Bitmap.createBitmap(750, 1000, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            assertNull(DocumentBoundary.detect(bitmap, listOf(OcrLine("", .4f, .4f, .6f, .6f))))
            assertNull(DocumentBoundary.detect(bitmap, emptyList()))
        } finally { bitmap.recycle() }
    }

    @Test fun realPhotoBoundaryCoversEveryMlAnchorAndPreservesSourceBytes() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val path = "real-receipt-photos/17_foto_girada.jpg"
        val source = assets.open(path).use { it.readBytes() }
        val before = MessageDigest.getInstance("SHA-256").digest(source)
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
        val orientation = source.inputStream().use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val bitmap = if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                Matrix().apply { setRotate(90f) }, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        try {
            val anchors = OfflineOcr().use { reader -> reader.recognize(bitmap).let { it.lines + it.words } }
            val result = DocumentBoundary.detect(bitmap, anchors)
            assertNotNull(result)
            assertTrue(result!!.uncertaintyPixels >= 8)
            assertTrue(result.minimumBorderSupport >= .70f)
            assertTrue(anchors.none { result.definitelyOutside(it) })
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(assets.open(path).use { it.readBytes() }))
            assertFalse(bitmap.isRecycled)
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }
}

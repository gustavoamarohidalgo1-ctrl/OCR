package com.facturaclara.app

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** A complete temporary raster with slowly varying illumination divided out. No text input. */
internal object LineIllumination {
    fun normalize(source: Bitmap): Bitmap {
        require(!source.isRecycled && source.width.toLong() * source.height <= 4_000_000L)
        check(OpenCVLoader.initLocal())
        val rgba = Mat()
        val gray = Mat()
        val values = Mat()
        val estimator = Mat()
        val smooth = Mat()
        val background = Mat()
        val normalized = Mat()
        try {
            checkpoint()
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            gray.convertTo(values, CvType.CV_32F)
            // Bound the background estimator's work, while keeping every source
            // pixel in the output. Tall narrow word boxes cannot create huge kernels.
            val scale = minOf(1.0, 128.0 / source.height, 2048.0 / source.width)
            val size = Size(maxOf(1, (source.width * scale).roundToInt()).toDouble(),
                maxOf(1, (source.height * scale).roundToInt()).toDouble())
            Imgproc.resize(values, estimator, size, 0.0, 0.0, Imgproc.INTER_AREA)
            val sigma = maxOf(3.0, source.height * .5) * scale
            checkpoint()
            Imgproc.GaussianBlur(estimator, smooth, Size(0.0, 0.0), sigma, sigma, Core.BORDER_REPLICATE)
            checkpoint()
            Imgproc.resize(smooth, background, values.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.max(background, Scalar(1.0), background)
            Core.divide(values, background, normalized, 255.0)
            val samples = FloatArray(source.width * source.height)
            normalized.get(0, 0, samples)
            require(samples.all { it.isFinite() }) { "Invalid illumination raster" }
            checkpoint()
            val pixels = IntArray(samples.size) { index ->
                val level = samples[index].toInt().coerceIn(0, 255)
                0xff000000.toInt() or (level shl 16) or (level shl 8) or level
            }
            return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
        } finally {
            normalized.release(); background.release(); smooth.release(); estimator.release()
            values.release(); gray.release(); rgba.release()
        }
    }
    private fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Preparación de lectura interrumpida.")
    }
}

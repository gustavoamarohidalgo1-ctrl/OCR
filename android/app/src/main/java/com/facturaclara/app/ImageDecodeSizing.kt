package com.facturaclara.app

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Keeps photo detail within the OCR budget without rounding every reduction to a factor of two. */
internal object ImageDecodeSizing {
    private const val DENSITY = 1_000_000

    data class Plan(
        val sampleSize: Int,
        val sourceDensity: Int,
        val targetDensity: Int,
        val width: Int,
        val height: Int,
        val intermediatePixels: Long,
    ) {
        val pixels: Long get() = width.toLong() * height
        val scales: Boolean get() = targetDensity != sourceDensity
    }

    fun plan(width: Int, height: Int, pixelBudget: Long, maxSide: Int): Plan {
        require(width > 0 && height > 0 && pixelBudget > 0 && maxSide > 0)
        var sample = 1
        while (true) {
            // Some codecs round sampled dimensions up. Use that upper bound for
            // both the density calculation and the transient allocation budget.
            val sampledWidth = ((width.toLong() + sample - 1) / sample).toInt()
            val sampledHeight = ((height.toLong() + sample - 1) / sample).toInt()
            val intermediate = sampledWidth.toLong() * sampledHeight
            val scale = minOf(1.0, sqrt(pixelBudget.toDouble() / intermediate),
                maxSide.toDouble() / maxOf(sampledWidth, sampledHeight))
            var density = floor(scale * DENSITY).toInt().coerceIn(1, DENSITY)
            fun dimension(size: Int) = (size * (density.toDouble() / DENSITY)).roundToInt().coerceAtLeast(1)
            var targetWidth = dimension(sampledWidth)
            var targetHeight = dimension(sampledHeight)
            // BitmapFactory rounds density-scaled dimensions to the nearest
            // integer. Do not let that rounding exceed a hard limit.
            while (targetWidth.toLong() * targetHeight > pixelBudget ||
                maxOf(targetWidth, targetHeight) > maxSide) {
                density--
                check(density > 0) { "No se pudo calcular un tamaño seguro para la imagen." }
                targetWidth = dimension(sampledWidth)
                targetHeight = dimension(sampledHeight)
            }
            val outputPixels = targetWidth.toLong() * targetHeight
            // Native density scaling may hold its sampled bitmap AND its output
            // simultaneously. Three ARGB buffers per budget consume at most half
            // the heap with the worker's maxMemory / 24 budget. No full-size
            // temporary is allowed to escape that reserve.
            if (intermediate + outputPixels <= pixelBudget * 3 &&
                maxOf(sampledWidth, sampledHeight).toLong() <= maxSide.toLong() * 2) {
                return Plan(sample, DENSITY, density, targetWidth, targetHeight, intermediate)
            }
            check(sample <= Int.MAX_VALUE / 2) { "La imagen tiene dimensiones no compatibles." }
            sample *= 2
        }
    }
}

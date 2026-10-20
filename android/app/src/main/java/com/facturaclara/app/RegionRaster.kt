package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Temporary geometric views of the imported pixels; never modifies the source. */
internal object RegionRaster {
    data class Prepared(val bitmap: Bitmap, val inverse: Matrix)

    fun prepare(source: Bitmap, lines: List<OcrLine>, scale: Double, deskew: Boolean,
                contrast: Boolean, tight: Boolean = true): Prepared {
        require(lines.isNotEmpty())
        val heights = lines.map { (it.bottom - it.top) * source.height }.sorted()
        val height = heights[heights.size / 2]
        val verticalMargin = maxOf(8f, height * .75f)
        val horizontalMargin = maxOf(12f, height * 1.5f)
        val rectangle = RectF(
            if (tight) floor(lines.minOf { it.left } * source.width - horizontalMargin).coerceAtLeast(0f) else 0f,
            floor(lines.minOf { it.top } * source.height - verticalMargin).coerceAtLeast(0f),
            if (tight) ceil(lines.maxOf { it.right } * source.width + horizontalMargin).coerceAtMost(source.width.toFloat()) else source.width.toFloat(),
            ceil(lines.maxOf { it.bottom } * source.height + verticalMargin).coerceAtMost(source.height.toFloat()),
        )
        val angles = lines.filter { it.text.length >= 4 }.map { line ->
            Math.toDegrees(atan(line.slope.toDouble() * source.height / source.width)).toFloat() to
                ((line.right - line.left) * source.width).toDouble().let { it * it }
        }.filter { abs(it.first) <= 15f }
        val half = angles.sumOf { it.second } / 2
        var accumulated = 0.0
        val median = angles.sortedBy { it.first }.firstOrNull { accumulated += it.second; accumulated >= half }?.first ?: 0f
        val degrees = if (deskew) -median else 0f
        val padded = RectF(0f, 0f, rectangle.width() + 48, rectangle.height() + 48)
        val rotated = RectF(padded)
        Matrix().apply { setRotate(degrees) }.mapRect(rotated)
        val factor = minOf(scale, sqrt(4_000_000.0 / (rotated.width().toDouble() * rotated.height())))
        val transform = Matrix().apply {
            setTranslate(-rectangle.left + 24, -rectangle.top + 24)
            postRotate(degrees)
            postTranslate(-rotated.left, -rotated.top)
            postScale(factor.toFloat(), factor.toFloat())
        }
        val inverse = Matrix().also { check(transform.invert(it)) }
        val bitmap = Bitmap.createBitmap(ceil(rotated.width() * factor).toInt().coerceAtLeast(1),
            ceil(rotated.height() * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (contrast) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                val c = 1.18f
                val offset = 128f * (1 - c)
                matrix.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset,0f,c,0f,0f,offset,
                    0f,0f,c,0f,offset,0f,0f,0f,1f,0f)))
                paint.colorFilter = ColorMatrixColorFilter(matrix)
            }
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                save(); concat(transform); clipRect(rectangle)
                drawBitmap(source, 0f, 0f, paint)
                restore()
            }
            bitmap.density = Bitmap.DENSITY_NONE
            return Prepared(bitmap, inverse)
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    fun map(line: OcrLine, prepared: Prepared, width: Int, height: Int): OcrLine? {
        val box = RectF(line.left * prepared.bitmap.width, line.top * prepared.bitmap.height,
            line.right * prepared.bitmap.width, line.bottom * prepared.bitmap.height)
        val baseline = floatArrayOf(box.centerX(), box.centerY(), box.centerX() + 10f,
            box.centerY() + 10f * line.slope * prepared.bitmap.height / prepared.bitmap.width)
        prepared.inverse.mapPoints(baseline)
        prepared.inverse.mapRect(box)
        val left = (box.left / width).coerceIn(0f, 1f)
        val right = (box.right / width).coerceIn(0f, 1f)
        val top = (box.top / height).coerceIn(0f, 1f)
        val bottom = (box.bottom / height).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        val dx = baseline[2] - baseline[0]
        val slope = if (abs(dx) > .001f) (baseline[3] - baseline[1]) / dx * width / height else 0f
        return line.copy(left = left, top = top, right = right, bottom = bottom, slope = slope)
    }
}

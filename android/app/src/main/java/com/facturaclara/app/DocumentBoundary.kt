package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Optional evidence of a visible paper boundary. Pixels propose the boundary;
 * OCR positions only veto candidates which would exclude already detected text.
 * No source pixels, OCR strings or recognition decisions are changed here.
 * Abstention is intentional for clipped, curved, ambiguous or unsupported edges.
 */
internal object DocumentBoundary {
    data class Detection(
        /** Four cyclic vertices in normalized original-image coordinates. */
        val points: List<PointF>,
        /** Conservative expansion measured in pixels of the bounded detection raster. */
        val uncertaintyPixels: Float,
        val rasterWidth: Int,
        val rasterHeight: Int,
        val minimumBorderSupport: Float,
        val maximumCornerVariationPixels: Float,
    ) {
        /** A whole box must lie beyond one expanded supporting half-plane. */
        fun definitelyOutside(line: OcrLine): Boolean {
            if (!valid(line) || points.size != 4 || rasterWidth <= 0 || rasterHeight <= 0 ||
                !uncertaintyPixels.isFinite() || uncertaintyPixels < 8f) return false
            val polygon = points.map { Point(it.x.toDouble() * rasterWidth, it.y.toDouble() * rasterHeight) }
            if (polygon.any { !it.x.isFinite() || !it.y.isFinite() }) return false
            val center = centroid(polygon)
            val corners = listOf(
                Point(line.left.toDouble() * rasterWidth, line.top.toDouble() * rasterHeight),
                Point(line.right.toDouble() * rasterWidth, line.top.toDouble() * rasterHeight),
                Point(line.right.toDouble() * rasterWidth, line.bottom.toDouble() * rasterHeight),
                Point(line.left.toDouble() * rasterWidth, line.bottom.toDouble() * rasterHeight),
            )
            // Merely checking four exterior corners is insufficient: their box
            // could contain or cross the entire document. A separating side is required.
            return polygon.indices.any { index ->
                val first = polygon[index]
                val second = polygon[(index + 1) % polygon.size]
                if (hypot(second.x - first.x, second.y - first.y) < 1.0) false
                else corners.maxOf { signedDistance(it, first, second, center) } < -uncertaintyPixels
            }
        }
    }

    private data class Candidate(
        val threshold: Int,
        val points: List<Point>,
        val residual: Double,
        val minimumSupport: Double,
    )

    /** Pass all complete ML word positions and all proposed output anchors. */
    @Throws(InterruptedException::class)
    fun detect(original: Bitmap, anchors: List<OcrLine>): Detection? {
        checkpoint()
        if (original.isRecycled || original.width < 32 || original.height < 32 ||
            anchors.isEmpty() || anchors.size > MAX_ANCHORS || anchors.any { !valid(it) }) return null
        return try {
            if (!OpenCVLoader.initLocal()) null else detectInitialized(original, anchors)
        } catch (error: InterruptedException) {
            throw error
        } catch (_: LinkageError) {
            // This is an optional proof; failure must retain the orphan veto.
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun detectInitialized(original: Bitmap, anchors: List<OcrLine>): Detection? {
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(original.width, original.height))
        val width = (original.width * scale).roundToInt().coerceAtLeast(1)
        val height = (original.height * scale).roundToInt().coerceAtLeast(1)
        val reduced = if (width == original.width && height == original.height) original
            else Bitmap.createScaledBitmap(original, width, height, true)
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            checkpoint()
            Utils.bitmapToMat(reduced, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            rgba.release()
            gray.release()
            val anchorCorners = anchors.flatMap { line ->
                listOf(Point(line.left.toDouble() * width, line.top.toDouble() * height),
                    Point(line.right.toDouble() * width, line.top.toDouble() * height),
                    Point(line.right.toDouble() * width, line.bottom.toDouble() * height),
                    Point(line.left.toDouble() * width, line.bottom.toDouble() * height))
            }
            val candidates = mutableListOf<Candidate>()
            THRESHOLDS.forEachIndexed { threshold, (low, high) ->
                checkpoint()
                val edge = Mat()
                val closed = Mat()
                val hierarchy = Mat()
                val contours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.Canny(blurred, edge, low, high)
                    val edgePixels = ByteArray(width * height)
                    edge.get(0, 0, edgePixels)
                    Imgproc.morphologyEx(edge, closed, Imgproc.MORPH_CLOSE, kernel)
                    Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    if (contours.size > MAX_CONTOURS) return null
                    for (contour in contours) {
                        checkpoint()
                        val area = Imgproc.contourArea(contour) / (width.toDouble() * height)
                        if (area !in 0.10..0.90) continue
                        val raw = contour.toArray()
                        if (raw.any { it.x < FRAME_MARGIN || it.y < FRAME_MARGIN ||
                            it.x > width - 1 - FRAME_MARGIN || it.y > height - 1 - FRAME_MARGIN }) continue
                        val curve = MatOfPoint2f(*raw)
                        val approximation = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approximation, .02 * Imgproc.arcLength(curve, true), true)
                            if (approximation.total() != 4L) continue
                            val approximatePoints = approximation.toArray()
                            val convex = MatOfPoint(*approximatePoints)
                            try { if (!Imgproc.isContourConvex(convex)) continue }
                            finally { convex.release() }
                            val points = ordered(approximatePoints.toList())
                            val center = centroid(points)
                            if (anchorCorners.any { p -> points.indices.any { index ->
                                signedDistance(p, points[index], points[(index + 1) % 4], center) < 0
                            } }) continue
                            val residual = raw.maxOf { p -> points.indices.minOf { index ->
                                segmentDistance(p, points[index], points[(index + 1) % 4])
                            } }
                            if (residual > .04 * minOf(width, height)) continue
                            val support = borderSupport(points, edgePixels, width, height, ceil(residual).toInt() + 2)
                                ?: continue
                            candidates += Candidate(threshold, points, residual, support)
                            if (candidates.size > MAX_CANDIDATES) return null
                        } finally { curve.release(); approximation.release() }
                    }
                } finally {
                    contours.forEach { it.release() }
                    edge.release(); closed.release(); hierarchy.release()
                }
            }
            checkpoint()
            if (candidates.isEmpty() || candidates.map { it.threshold }.toSet().size != THRESHOLDS.size) return null
            val first = candidates.first().points
            val variation = candidates.maxOf { candidate -> first.indices.maxOf { index ->
                hypot(first[index].x - candidate.points[index].x, first[index].y - candidate.points[index].y)
            } }
            // Multiple materially different polygons must not be resolved by area preference.
            if (variation > MAX_CORNER_VARIATION) return null
            val points = first.indices.map { index ->
                PointF((median(candidates.map { it.points[index].x }) / width).toFloat(),
                    (median(candidates.map { it.points[index].y }) / height).toFloat())
            }
            val uncertainty = 8 + candidates.maxOf { it.residual } + variation
            return Detection(points, uncertainty.toFloat(), width, height,
                candidates.minOf { it.minimumSupport }.toFloat(), variation.toFloat())
        } finally {
            kernel.release(); rgba.release(); gray.release(); blurred.release()
            if (reduced !== original && !reduced.isRecycled) reduced.recycle()
        }
    }

    private fun borderSupport(points: List<Point>, pixels: ByteArray, width: Int, height: Int, radius: Int): Double? {
        var minimum = 1.0
        for (index in points.indices) {
            checkpoint()
            val first = points[index]
            val second = points[(index + 1) % 4]
            val dx = second.x - first.x
            val dy = second.y - first.y
            val length = hypot(dx, dy)
            if (length < 1) return null
            val nx = -dy / length
            val ny = dx / length
            val samples = ceil(length / 5).toInt().coerceIn(32, 128)
            var supported = 0
            var gap = 0
            var longestGap = 0
            for (sample in 0 until samples) {
                val t = .02 + .96 * sample / (samples - 1)
                val px = first.x + t * dx
                val py = first.y + t * dy
                var found = false
                for (offset in -radius..radius) {
                    val x = Math.rint(px + offset * nx).toInt()
                    val y = Math.rint(py + offset * ny).toInt()
                    if (x < 1 || y < 1 || x >= width - 1 || y >= height - 1) continue
                    for (yy in y - 1..y + 1) for (xx in x - 1..x + 1) {
                        if (pixels[yy * width + xx] != 0.toByte()) found = true
                    }
                    if (found) break
                }
                if (found) { supported++; gap = 0 } else { gap++; longestGap = maxOf(longestGap, gap) }
            }
            val fraction = supported.toDouble() / samples
            if (fraction < .70 || longestGap.toDouble() / samples > .20) return null
            minimum = minOf(minimum, fraction)
        }
        return minimum
    }

    private fun ordered(points: List<Point>): List<Point> {
        val center = centroid(points)
        val sorted = points.sortedBy { atan2(it.y - center.y, it.x - center.x) }
        val first = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y }!!
        return sorted.indices.map { sorted[(first + it) % sorted.size] }
    }

    private fun centroid(points: List<Point>): Point = Point(points.map { it.x }.average(), points.map { it.y }.average())

    private fun signedDistance(point: Point, first: Point, second: Point, center: Point): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val length = hypot(dx, dy)
        if (length < 1e-9) return Double.NaN
        val sign = if (-dy * (center.x - first.x) + dx * (center.y - first.y) >= 0) 1 else -1
        return sign * (-dy * (point.x - first.x) + dx * (point.y - first.y)) / length
    }

    private fun segmentDistance(point: Point, first: Point, second: Point): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val squared = dx * dx + dy * dy
        if (squared <= 0) return Double.POSITIVE_INFINITY
        val fraction = (((point.x - first.x) * dx + (point.y - first.y) * dy) / squared).coerceIn(0.0, 1.0)
        return hypot(point.x - first.x - fraction * dx, point.y - first.y - fraction * dy)
    }

    private fun median(values: List<Double>): Double = values.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    private fun valid(line: OcrLine): Boolean = listOf(line.left, line.top, line.right, line.bottom).all { it.isFinite() && it in 0f..1f } &&
        line.left < line.right && line.top < line.bottom

    private fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Comprobación del borde interrumpida.")
    }

    private const val MAX_SIDE = 1000
    private const val FRAME_MARGIN = 8
    private const val MAX_ANCHORS = 6000
    private const val MAX_CONTOURS = 20_000
    private const val MAX_CANDIDATES = 24
    private const val MAX_CORNER_VARIATION = 8.0
    private val THRESHOLDS = listOf(20.0 to 60.0, 30.0 to 90.0, 60.0 to 180.0)
}

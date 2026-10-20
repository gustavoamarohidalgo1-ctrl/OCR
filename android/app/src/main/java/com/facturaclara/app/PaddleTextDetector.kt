// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
// Portions adapted from PaddleOCR DBPostProcessor, PolygonUnclip, QuadGeometry,
// and the official model's DetResizeForTest / NormalizeImage preprocessing.
// Licensed under the Apache License, Version 2.0. See assets/paddle/LICENSE.
// Modifications: independent geometry API, verified bundled model, bounded
// resources, cancellation, normalized coordinates and immutable input ownership.
package com.facturaclara.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline text detection from the complete page's pixels, independent of every
 * recognizer and every existing OCR box. No text or reference enters this class.
 * Keep the caller-owned upright bitmap alive until detect/rectify returns.
 * cancel() may run on any thread; close() releases native objects on the owner.
 */
internal class PaddleTextDetector(context: Context, val profile: Profile = Profile.V5_MOBILE) : Closeable {
    /** Pinned model-specific YAML defaults; no thresholds depend on the photo. */
    enum class Profile(
        val modelAsset: String,
        val modelSha: String,
        val threshold: Float,
        val boxThreshold: Float,
        val unclipRatio: Double,
        val maxCandidates: Int,
        val maxRasterSide: Int,
        val maxRasterPixels: Long,
    ) {
        V5_MOBILE(MODEL_ASSET, MODEL_SHA, THRESHOLD, BOX_THRESHOLD, UNCLIP_RATIO, MAX_CANDIDATES, 1024, 1_048_576),
        V6_SMALL("paddle/detection-v6-small.onnx", "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            .2f, .45f, 1.4, 3000, 4000, 4_000_000),
    }

    val modelSha: String get() = profile.modelSha
    val settings: Map<String, Any> get() = linkedMapOf<String, Any>(
        "profile" to profile.name, "channel_order" to "BGR", "normalization" to "ImageNet mean/std, scale 1/255",
        "threshold" to profile.threshold, "box_threshold" to profile.boxThreshold,
        "unclip_ratio" to profile.unclipRatio, "max_candidates" to profile.maxCandidates,
        "box_type" to "quad", "score_mode" to "fast", "dilation" to false,
        "resource_max_raster_pixels" to profile.maxRasterPixels, "resource_max_regions" to MAX_REGIONS,
        "resource_overflow" to "reject complete page; never truncate",
    ).apply {
        when (profile) {
            Profile.V5_MOBILE -> { put("resize_long", RESIZE_LONG); put("resize_stride", STRIDE); put("resize_rounding", "floor then ceil stride") }
            Profile.V6_SMALL -> { put("limit_side_len", 736); put("limit_type", "min"); put("max_side_limit", 4000)
                put("resize_stride", 32); put("resize_rounding", "floor then round half to even")
                put("pixel_budget_resize", "area scale, floor to stride") }
        }
    }

    data class Detection(
        /** TL, TR, BR, BL, normalized against the original upright bitmap. */
        val points: List<PointF>,
        val confidence: Float,
        /** Blank text; the geometry is independent detection evidence only. */
        val line: OcrLine,
        /** Detector quads may contain vertical lines; upright ML word boxes must opt out. */
        val rotateVertical: Boolean = true,
    )

    data class Result(
        val detections: List<Detection>,
        val inputWidth: Int,
        val inputHeight: Int,
        val contourCount: Int,
        val elapsedMs: Long,
        val profile: Profile,
        val modelSha: String,
        val settings: Map<String, Any>,
        val probabilityMinimum: Float,
        val probabilityMaximum: Float,
        val probabilityClampedCount: Int,
    )

    private val assets = context.applicationContext.assets
    private val ownerLock = Any()
    private val lifecycleLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var activeRun: OrtSession.RunOptions? = null
    private var session: OrtSession? = null
    // Process-wide environment: closing this would affect the other OCR reader.
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    fun detect(original: Bitmap): Result = synchronized(ownerLock) {
        validate(original)
        val started = SystemClock.elapsedRealtime()
        check(OpenCVLoader.initLocal()) { "OpenCV unavailable for independent text detection" }
        val input = preprocess(original)
        ensureSession()
        val probabilities = infer(input)
        checkpoint()
        val result = postprocess(probabilities.values, input.width, input.height, original.width, original.height)
        checkpoint()
        Result(result.first, input.width, input.height, result.second, SystemClock.elapsedRealtime() - started,
            profile, modelSha, settings, probabilities.summary.rawMinimum, probabilities.summary.rawMaximum, probabilities.summary.clampedCount)
    }

    /** Perspective rectification of a copy. The returned bitmap belongs to the caller. */
    fun rectify(original: Bitmap, detection: Detection, marginRatio: Float = 0f): Bitmap = synchronized(ownerLock) {
        validate(original)
        check(OpenCVLoader.initLocal()) { "OpenCV unavailable for text rectification" }
        val geometry = cropGeometry(original, detection, marginRatio)
        val points = geometry.points
        val width = geometry.width
        val height = geometry.height
        val left = geometry.left
        val top = geometry.top
        val cropped = Bitmap.createBitmap(original, left, top, geometry.right - left, geometry.bottom - top)
        val source = Mat()
        val warped = Mat()
        val rotated = Mat()
        val sourcePoints = MatOfPoint2f(*points.map { Point(it.x - left, it.y - top) }.toTypedArray())
        val targetPoints = targetPoints(width, height)
        var transform: Mat? = null
        var output: Bitmap? = null
        try {
            checkpoint()
            Utils.bitmapToMat(cropped, source)
            transform = Imgproc.getPerspectiveTransform(sourcePoints, targetPoints)
            Imgproc.warpPerspective(source, warped, transform, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            checkpoint()
            val finalMat = if (geometry.rotated) {
                Core.rotate(warped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                rotated
            } else warped
            output = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(finalMat, output)
            output.density = Bitmap.DENSITY_NONE
            checkpoint()
            output
        } catch (error: Throwable) {
            output?.recycle()
            throw error
        } finally {
            transform?.release(); targetPoints.release(); sourcePoints.release()
            rotated.release(); warped.release(); source.release()
            if (cropped !== original && !cropped.isRecycled) cropped.recycle()
        }
    }

    /** Map all four crop-word edges back through the same perspective/rotation. */
    fun mapFromRectified(original: Bitmap, detection: Detection, word: OcrLine, marginRatio: Float = 0f): OcrLine? = synchronized(ownerLock) {
        validate(original)
        if (listOf(word.left, word.top, word.right, word.bottom).any { !it.isFinite() || it !in 0f..1f } ||
            word.left >= word.right || word.top >= word.bottom) return@synchronized null
        check(OpenCVLoader.initLocal()) { "OpenCV unavailable for inverse text geometry" }
        val geometry = cropGeometry(original, detection, marginRatio)
        val normalized = listOf(Point(word.left.toDouble(), word.top.toDouble()), Point(word.right.toDouble(), word.top.toDouble()),
            Point(word.right.toDouble(), word.bottom.toDouble()), Point(word.left.toDouble(), word.bottom.toDouble()))
        // These are rectangle edges, hence width-y rather than width-1-y when
        // undoing the CCW pixel rotation. Full crop edges map to the source quad.
        val unrotated = normalized.map { if (geometry.rotated) Point((1 - it.y) * geometry.width, it.x * geometry.height)
            else Point(it.x * geometry.width, it.y * geometry.height) }
        val target = targetPoints(geometry.width, geometry.height)
        val source = MatOfPoint2f(*geometry.points.toTypedArray())
        val input = MatOfPoint2f(*unrotated.toTypedArray())
        val output = MatOfPoint2f()
        var inverse: Mat? = null
        try {
            inverse = Imgproc.getPerspectiveTransform(target, source)
            Core.perspectiveTransform(input, output, inverse)
            checkpoint()
            val mapped = output.toArray().map { Point(it.x / original.width, it.y / original.height) }
            if (mapped.size != 4 || mapped.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in -1e-6..1.000001 || it.y !in -1e-6..1.000001 }) return@synchronized null
            val dx = mapped[1].x - mapped[0].x
            val slope = if (abs(dx) > 1e-6) ((mapped[1].y - mapped[0].y) / dx).toFloat() else 0f
            word.copy(left = mapped.minOf { it.x }.coerceIn(0.0, 1.0).toFloat(), top = mapped.minOf { it.y }.coerceIn(0.0, 1.0).toFloat(),
                right = mapped.maxOf { it.x }.coerceIn(0.0, 1.0).toFloat(), bottom = mapped.maxOf { it.y }.coerceIn(0.0, 1.0).toFloat(), slope = slope)
        } finally { inverse?.release(); output.release(); input.release(); source.release(); target.release() }
    }

    /**
     * A proximity owner is not proof that its crop contains the complete word.
     * Require every source AABB corner inside the exact crop quadrilateral.
     * The fixed one-pixel allowance covers rounded source-coordinate edges only;
     * it never grows with word size, overlap, confidence or proposed text.
     */
    fun coversSourceWord(original: Bitmap, detection: Detection, word: OcrLine, marginRatio: Float = 0f): Boolean = synchronized(ownerLock) {
        validate(original)
        val polygon = cropGeometry(original, detection, marginRatio).points
        val corners = listOf(
            Point(word.left.toDouble() * original.width, word.top.toDouble() * original.height),
            Point(word.right.toDouble() * original.width, word.top.toDouble() * original.height),
            Point(word.right.toDouble() * original.width, word.bottom.toDouble() * original.height),
            Point(word.left.toDouble() * original.width, word.bottom.toDouble() * original.height),
        )
        val covered = polygon.indices.all { index ->
            val a = polygon[index]
            val b = polygon[(index + 1) % polygon.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = distance(a, b)
            corners.all { point ->
                val signedDistance = (dx * (point.y - a.y) - dy * (point.x - a.x)) / length
                signedDistance.isFinite() && signedDistance >= -1.0
            }
        }
        checkpoint()
        covered
    }

    private data class CropGeometry(val points: List<Point>, val width: Int, val height: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int, val rotated: Boolean)

    private fun targetPoints(width: Int, height: Int) = MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0),
        Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble()))

    private fun cropGeometry(original: Bitmap, detection: Detection, marginRatio: Float): CropGeometry {
        require(marginRatio.isFinite() && marginRatio in 0f..0.25f) { "Invalid text crop margin" }
        val sourceQuad = detection.points.map { Point(it.x.toDouble() * original.width, it.y.toDouble() * original.height) }
        validateQuad(detection.points, sourceQuad)
        val points = if (marginRatio == 0f) sourceQuad else {
            val margin = maxOf(distance(sourceQuad[0], sourceQuad[3]), distance(sourceQuad[1], sourceQuad[2])) * marginRatio
            offsetQuad(sourceQuad, margin).map { Point(it.x.coerceIn(0.0, original.width - 1.0), it.y.coerceIn(0.0, original.height - 1.0)) }
                .also { expanded -> validateQuad(expanded.map { PointF((it.x / original.width).toFloat(), (it.y / original.height).toFloat()) }, expanded) }
        }
        val width = (maxOf(distance(points[0], points[1]), distance(points[2], points[3])) + 1e-3).toInt().coerceAtLeast(1)
        val height = (maxOf(distance(points[0], points[3]), distance(points[1], points[2])) + 1e-3).toInt().coerceAtLeast(1)
        require(width <= MAX_SOURCE_SIDE && height <= MAX_SOURCE_SIDE && width.toLong() * height <= MAX_CROP_PIXELS) {
            "Independent text crop exceeds resource limit"
        }
        // Copy only the bounding rectangle, never a complete page for each line.
        val left = floor(points.minOf { it.x }).toInt().coerceIn(0, original.width - 1)
        val top = floor(points.minOf { it.y }).toInt().coerceIn(0, original.height - 1)
        val right = ceil(points.maxOf { it.x }).toInt().coerceIn(left + 1, original.width)
        val bottom = ceil(points.maxOf { it.y }).toInt().coerceIn(top + 1, original.height)
        require((right - left).toLong() * (bottom - top) <= MAX_CROP_PIXELS) { "Text crop bounds exceed resource limit" }
        return CropGeometry(points, width, height, left, top, right, bottom,
            detection.rotateVertical && height.toDouble() / width >= 1.5)
    }

    fun cancel() = synchronized(lifecycleLock) {
        cancelled = true
        try { activeRun?.setTerminate(true) } catch (_: OrtException) { /* Result remains rejected. */ }
    }

    override fun close() {
        cancel()
        synchronized(ownerLock) {
            if (!closed) {
                closed = true
                try { session?.close() } finally { session = null }
            }
        }
    }

    private fun checkpoint() {
        if (cancelled || closed || Thread.currentThread().isInterrupted) {
            throw CancellationException("Independent text detection cancelled")
        }
    }

    private fun validate(original: Bitmap) {
        checkpoint()
        require(!original.isRecycled && original.width >= 32 && original.height >= 32) { "Invalid detector input" }
        require(original.width <= MAX_SOURCE_SIDE && original.height <= MAX_SOURCE_SIDE &&
            original.width.toLong() * original.height <= MAX_SOURCE_PIXELS) { "Detector input exceeds resource limit" }
    }

    private fun validateQuad(normalized: List<PointF>, pixels: List<Point>) {
        require(normalized.size == 4 && normalized.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
        // Require a strictly convex clockwise image-coordinate polygon. Reject
        // crossings, duplicate points and an inverted transform before native work.
        require(pixels.indices.all { i ->
            val a = pixels[i]; val b = pixels[(i + 1) % 4]; val c = pixels[(i + 2) % 4]
            distance(a, b) >= 1.0 && (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 1e-6
        }) { "Invalid text quadrilateral" }
    }

    /** Intersections of parallel outward-offset edges; margin is glyph-height based. */
    private fun offsetQuad(points: List<Point>, margin: Double): List<Point> {
        val normals = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % points.size]
            val length = distance(a, b)
            Point((b.y - a.y) / length, -(b.x - a.x) / length)
        }
        return points.indices.map { i ->
            val previous = normals[(i + normals.size - 1) % normals.size]
            val next = normals[i]
            val denominator = 1 + previous.x * next.x + previous.y * next.y
            require(denominator.isFinite() && denominator > 1e-3) { "Degenerate margin quadrilateral" }
            Point(points[i].x + margin * (previous.x + next.x) / denominator,
                points[i].y + margin * (previous.y + next.y) / denominator)
        }
    }

    private fun ensureSession(): OrtSession {
        checkpoint()
        session?.let { return it }
        val model = assets.open(profile.modelAsset).use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            while (true) {
                checkpoint()
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_MODEL_BYTES) { "Detector model exceeds resource limit" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actualSha == profile.modelSha) { "Independent detector model integrity failed: ${profile.name}" }
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            checkpoint()
            environment.createSession(model, options)
        }
        try {
            checkpoint()
            require(created.inputNames == setOf("x") && created.numOutputs == 1L)
            val input = created.inputInfo.getValue("x").info as TensorInfo
            val output = created.outputInfo.values.single().info as TensorInfo
            require(input.type == OnnxJavaType.FLOAT && input.shape.size == 4 && input.shape[1] == 3L)
            require(output.type == OnnxJavaType.FLOAT && output.shape.size == 4 && output.shape[1] == 1L)
            session = created
            return created
        } catch (error: Throwable) { created.close(); throw error }
    }

    private data class Input(val width: Int, val height: Int, val values: FloatArray)

    private fun preprocess(original: Bitmap): Input {
        val (width, height) = rasterSize(profile, original.width, original.height)
        require(width in 32..profile.maxRasterSide && height in 32..profile.maxRasterSide &&
            width.toLong() * height <= profile.maxRasterPixels) {
            "Detector raster resource limit exceeded for ${profile.name}: ${width}x$height; page not complete"
        }
        val source = Mat()
        val resized = Mat()
        val bgr = Mat()
        try {
            checkpoint()
            Utils.bitmapToMat(original, source)
            Imgproc.resize(source, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            source.release()
            Imgproc.cvtColor(resized, bgr, Imgproc.COLOR_RGBA2BGR)
            resized.release()
            val pixels = ByteArray(width * height * 3)
            bgr.get(0, 0, pixels)
            val count = width * height
            val tensor = FloatArray(count * 3)
            for (c in 0..2) for (i in 0 until count) {
                if (i % 8192 == 0) checkpoint()
                tensor[c * count + i] = (((pixels[i * 3 + c].toInt() and 255) * (1f / 255f)) - MEAN[c]) / STD[c]
            }
            return Input(width, height, tensor)
        } finally { bgr.release(); resized.release(); source.release() }
    }

    private data class ProbabilityMap(val values: FloatArray, val summary: DetectorProbabilities.Summary)

    private fun infer(input: Input): ProbabilityMap {
        checkpoint()
        val floats = ByteBuffer.allocateDirect(input.values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        floats.put(input.values); floats.flip()
        val run = OrtSession.RunOptions()
        synchronized(lifecycleLock) {
            if (cancelled || closed || Thread.currentThread().isInterrupted) {
                run.close()
                throw CancellationException("Independent text detection cancelled")
            }
            activeRun = run
        }
        try {
            OnnxTensor.createTensor(environment, floats, longArrayOf(1, 3, input.height.toLong(), input.width.toLong())).use { tensor ->
                ensureSession().run(mapOf("x" to tensor), run).use { result ->
                    checkpoint()
                    val output = result[0] as? OnnxTensor ?: error("Detector output is not a tensor")
                    val shape = output.info.shape
                    require(shape.contentEquals(longArrayOf(1, 1, input.height.toLong(), input.width.toLong()))) { "Unexpected detector output shape" }
                    val buffer = output.floatBuffer
                    require(buffer.remaining() == input.width * input.height)
                    val values = FloatArray(buffer.remaining())
                    buffer.get(values)
                    return ProbabilityMap(values, DetectorProbabilities.validateInPlace(values, ::checkpoint))
                }
            }
        } catch (error: OrtException) {
            if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Independent text detection cancelled").apply { initCause(error) }
            throw error
        } finally {
            synchronized(lifecycleLock) { activeRun = null; run.close() }
        }
    }

    private fun postprocess(probabilities: FloatArray, width: Int, height: Int, originalWidth: Int, originalHeight: Int): Pair<List<Detection>, Int> {
        val probability = Mat(height, width, CvType.CV_32FC1)
        val mask = Mat(height, width, CvType.CV_8UC1)
        val threshold = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            probability.put(0, 0, probabilities)
            Imgproc.threshold(probability, threshold, profile.threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            threshold.convertTo(mask, CvType.CV_8UC1)
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            // The SDK truncates at its candidate budget. Here a partial page cannot silently
            // become a complete transcript, so exceeding that bound rejects it.
            require(contours.size <= profile.maxCandidates) { "Detector contour limit exceeded; page not complete" }
            val detections = mutableListOf<Detection>()
            for (contour in contours) {
                checkpoint()
                val raw = contour.toArray()
                if (raw.size < 3) continue
                val rectangle = minAreaBox(raw.toList()) ?: continue
                if (rectangle.second < 3.0) continue
                val confidence = boxScore(probability, rectangle.first)
                if (confidence < profile.boxThreshold) continue
                val expanded = minAreaBox(unclip(rectangle.first)) ?: continue
                if (expanded.second < 5.0) continue
                val sourcePoints = expanded.first.map { point -> Point(
                    Math.rint(point.x * originalWidth / width).coerceIn(0.0, originalWidth.toDouble()),
                    Math.rint(point.y * originalHeight / height).coerceIn(0.0, originalHeight.toDouble())) }
                // Match the predictor's final source-coordinate ordering and clipping.
                val first = sourcePoints.indices.minBy { sourcePoints[it].x + sourcePoints[it].y }
                val opposite = sourcePoints.indices.maxBy { sourcePoints[it].x + sourcePoints[it].y }
                if (first == opposite) continue
                val others = sourcePoints.indices.filter { it != first && it != opposite }
                val upperRight = others.minBy { sourcePoints[it].y - sourcePoints[it].x }
                val lowerLeft = others.maxBy { sourcePoints[it].y - sourcePoints[it].x }
                if (upperRight == lowerLeft) continue
                val mapped = listOf(first, upperRight, opposite, lowerLeft).map { index ->
                    val point = sourcePoints[index]
                    PointF(point.x.coerceIn(0.0, originalWidth - 1.0).toFloat() / originalWidth,
                        point.y.coerceIn(0.0, originalHeight - 1.0).toFloat() / originalHeight)
                }
                val pixelPoints = mapped.map { Point(it.x.toDouble() * originalWidth, it.y.toDouble() * originalHeight) }
                if (distance(pixelPoints[0], pixelPoints[1]).toInt() <= 3 || distance(pixelPoints[0], pixelPoints[3]).toInt() <= 3) continue
                try { validateQuad(mapped, pixelPoints) } catch (_: IllegalArgumentException) { continue }
                val left = mapped.minOf { it.x }; val right = mapped.maxOf { it.x }
                val top = mapped.minOf { it.y }; val bottom = mapped.maxOf { it.y }
                val dx = mapped[1].x - mapped[0].x
                val slope = if (abs(dx) > 1e-6f) (mapped[1].y - mapped[0].y) / dx else 0f
                detections += Detection(mapped, confidence, OcrLine("", left, top, right, bottom, slope, confidence))
                require(detections.size <= MAX_REGIONS) { "Detector region limit exceeded; page not complete" }
            }
            // Geometry-only ordering; callers remain responsible for table layout.
            return detections.sortedWith(compareBy<Detection> { it.line.top }.thenBy { it.line.left }) to contours.size
        } finally {
            contours.forEach { it.release() }; hierarchy.release(); threshold.release(); mask.release(); probability.release()
        }
    }

    private fun minAreaBox(points: List<Point>): Pair<List<Point>, Double>? {
        if (points.size < 3) return null
        val input = MatOfPoint2f(*points.toTypedArray())
        return try {
            val rectangle = Imgproc.minAreaRect(input)
            val corners = Array(4) { Point() }
            rectangle.points(corners)
            val sorted = corners.sortedBy { it.x }
            val left = sorted.take(2).sortedBy { it.y }
            val right = sorted.takeLast(2).sortedBy { it.y }
            listOf(left[0], right[0], right[1], left[1]) to minOf(rectangle.size.width, rectangle.size.height)
        } finally { input.release() }
    }

    private fun boxScore(probability: Mat, points: List<Point>): Float {
        val xmin = floor(points.minOf { it.x }).toInt().coerceIn(0, probability.cols() - 1)
        val xmax = ceil(points.maxOf { it.x }).toInt().coerceIn(0, probability.cols() - 1)
        val ymin = floor(points.minOf { it.y }).toInt().coerceIn(0, probability.rows() - 1)
        val ymax = ceil(points.maxOf { it.y }).toInt().coerceIn(0, probability.rows() - 1)
        val mask = Mat(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1, Scalar(0.0))
        val polygon = MatOfPoint(*points.map { Point((it.x - xmin).toInt().toDouble(), (it.y - ymin).toInt().toDouble()) }.toTypedArray())
        var roi: Mat? = null
        try {
            Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0))
            roi = probability.submat(ymin, ymax + 1, xmin, xmax + 1)
            return Core.mean(roi, mask).`val`[0].toFloat()
        } finally { roi?.release(); polygon.release(); mask.release() }
    }

    /** SDK round-join polygon offset, distance=area*1.5/perimeter. */
    private fun unclip(points: List<Point>): List<Point> {
        val signedArea = points.indices.sumOf { i ->
            val p = points[i]; val q = points[(i + 1) % points.size]; p.x * q.y - q.x * p.y
        } / 2
        val perimeter = points.indices.sumOf { distance(points[it], points[(it + 1) % points.size]) }
        if (!signedArea.isFinite() || !perimeter.isFinite() || abs(signedArea) <= 1e-6 || perimeter <= 1e-6) return points
        val offset = abs(signedArea) * profile.unclipRatio / perimeter
        val clockwise = signedArea > 0
        val normals = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % points.size]
            val length = distance(a, b)
            if (clockwise) Point((b.y - a.y) / length, -(b.x - a.x) / length)
            else Point(-(b.y - a.y) / length, (b.x - a.x) / length)
        }
        val arc = (2 * acos((1.0 - .25 / offset).coerceIn(-1.0, 1.0))).let { if (it.isFinite() && it > 1e-6) it else PI / 8 }
        val expanded = mutableListOf<Point>()
        for (i in points.indices) {
            val previous = normals[(i + normals.size - 1) % normals.size]
            val next = normals[i]
            val start = atan2(previous.y, previous.x)
            var end = atan2(next.y, next.x)
            if (clockwise) while (end < start) end += 2 * PI else while (end > start) end -= 2 * PI
            val sweep = end - start
            val steps = ceil(abs(sweep) / arc).toInt().coerceIn(1, 256)
            for (step in 0..steps) {
                val angle = start + sweep * step / steps
                expanded += Point(points[i].x + cos(angle) * offset, points[i].y + sin(angle) * offset)
            }
        }
        return expanded
    }

    private fun distance(a: Point, b: Point) = hypot(b.x - a.x, b.y - a.y)

    companion object {
        const val MODEL_SHA = "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
        const val MODEL_ASSET = "paddle/detection.onnx"
        const val RESIZE_LONG = 960
        const val STRIDE = 128
        const val THRESHOLD = .3f
        const val BOX_THRESHOLD = .6f
        const val UNCLIP_RATIO = 1.5
        const val MAX_CANDIDATES = 1000
        const val MAX_REGIONS = 256
        private const val MAX_MODEL_BYTES = 16 * 1024 * 1024
        private const val MAX_SOURCE_PIXELS = 12_000_000L
        private const val MAX_SOURCE_SIDE = 12_000
        private const val MAX_CROP_PIXELS = 4_000_000L
        private val MEAN = floatArrayOf(.485f, .456f, .406f)
        private val STD = floatArrayOf(.229f, .224f, .225f)

        /**
         * Model YAML resize rules. V6 then reduces by area when the raster would
         * exceed its pixel budget, instead of rejecting a camera photo: one scale
         * keeps the aspect ratio within one stride, and flooring both sides to the
         * stride keeps the product within the budget. postprocess maps detections
         * back to source coordinates for any raster size.
         */
        internal fun rasterSize(profile: Profile, sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> = when (profile) {
            Profile.V5_MOBILE -> {
                val ratio = RESIZE_LONG.toDouble() / maxOf(sourceWidth, sourceHeight)
                fun dimension(value: Int): Int = ((((value * ratio).toInt().coerceAtLeast(1)) + STRIDE - 1) / STRIDE) * STRIDE
                dimension(sourceWidth) to dimension(sourceHeight)
            }
            Profile.V6_SMALL -> {
                val ratio = maxOf(1.0, 736.0 / minOf(sourceWidth, sourceHeight))
                var width = (sourceWidth * ratio).toInt()
                var height = (sourceHeight * ratio).toInt()
                if (maxOf(width, height) > 4000) {
                    val fit = 4000.0 / maxOf(width, height)
                    width = (width * fit).toInt(); height = (height * fit).toInt()
                }
                fun round32(value: Int) = maxOf(32, Math.rint(value / 32.0).toInt() * 32)
                if (round32(width).toLong() * round32(height) <= profile.maxRasterPixels) round32(width) to round32(height)
                else {
                    val area = sqrt(profile.maxRasterPixels.toDouble() / (width.toLong() * height))
                    fun floor32(value: Int) = maxOf(32, floor(value * area / 32).toInt() * 32)
                    floor32(width) to floor32(height)
                }
            }
        }
    }
}

// Preprocessing and CTC decoding follow PaddleOCR (Copyright 2026 PaddlePaddle
// Authors, Apache-2.0). See assets/paddle/LICENSE and MODELS.json for provenance.
package com.facturaclara.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * Bundled, CPU-only visual recognizer. Call on an owning background thread, and
 * retain the caller-owned upright bitmap until recognize/recognizeRows returns.
 * This class never changes or recycles that bitmap and never uses prior text as
 * an inference input. Word boxes and line boxes use exactly the same API.
 * cancel() may run on another thread; only the owner should call close().
 */
internal class PaddleOcr(context: Context) : Closeable {
    enum class Channel { BGR, RGB }
    enum class Variant {
        TIGHT, MARGIN, DESKEW_MARGIN, WIDE_HORIZONTAL_DESKEW, WIDE_HORIZONTAL,
        ROW_MARGIN_HALF, ROW_MARGIN_ONE, ROW_MARGIN_TWO,
        PAGE_RECTIFIED,
    }
    data class CharacterObservation(val text: String, val confidence: Float, val timestep: Int, val classIndex: Int)
    data class Observation(
        val variant: Variant,
        val channel: Channel,
        val text: String,
        val confidence: Float,
        val line: OcrLine,
        val characters: List<CharacterObservation>,
        val tensorWidth: Int,
        val elapsedMs: Long,
        /** SHA-256 of resized RGB pixels and dimensions, independent of variant/channel labels. */
        val rasterId: String,
    )
    data class FragmentObservations(val index: Int, val fragment: OcrLine, val observations: List<Observation>)

    private val assets = context.applicationContext.assets
    private val ownerLock = Any()
    private val lifecycleLock = Any()
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var activeRun: OrtSession.RunOptions? = null
    private var session: OrtSession? = null
    private var alphabet: List<String> = emptyList()
    // OrtEnvironment is process-wide; closing it here would affect other readers.
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    /** A complete patch from an independent detector; no ML geometry or text is used. */
    fun recognizeRectified(patch: Bitmap, geometry: OcrLine): Observation = synchronized(ownerLock) {
        validate(patch, 1, listOf(Channel.BGR))
        ensureSession()
        val raster = resize(readRaster(patch, 0, 0, patch.width, patch.height))
        infer(raster, geometry.copy(text = "", confidence = null), Variant.PAGE_RECTIFIED,
            Channel.BGR, rasterDigest(raster)).also { checkpoint() }
    }

    fun recognize(
        original: Bitmap,
        fragments: List<OcrLine>,
        channels: List<Channel> = listOf(Channel.BGR),
    ): List<FragmentObservations> = synchronized(ownerLock) {
        validate(original, fragments.size, channels)
        if (fragments.isEmpty()) return@synchronized emptyList()
        ensureSession()
        fragments.mapIndexed { index, fragment ->
            checkpoint()
            val observations = mutableListOf<Observation>()
            val cache = mutableMapOf<Pair<String, Channel>, Observation>()
            for (variant in FRAGMENT_VARIANTS) {
                val raster = resize(fragmentRaster(original, fragment, variant))
                observe(raster, fragment, variant, channels, cache, observations)
            }
            FragmentObservations(index, fragment, observations)
        }.also { checkpoint() }
    }

    /** Geometry-only row rectification and margins from the independent row probe. */
    fun recognizeRows(
        original: Bitmap,
        rows: List<List<OcrLine>>,
        channels: List<Channel> = listOf(Channel.BGR),
    ): List<FragmentObservations> = synchronized(ownerLock) {
        validate(original, rows.size, channels)
        require(rows.all { it.isNotEmpty() } && rows.sumOf { it.size.toLong() } <= MAX_ROW_FRAGMENTS)
        if (rows.isEmpty()) return@synchronized emptyList()
        ensureSession()
        rows.mapIndexed { index, row ->
            checkpoint()
            val slope = weightedSlope(row)
            val fragment = OcrLine("", row.minOf { it.left }, row.minOf { it.top },
                row.maxOf { it.right }, row.maxOf { it.bottom }, slope)
            val observations = mutableListOf<Observation>()
            val cache = mutableMapOf<Pair<String, Channel>, Observation>()
            for ((variant, margin) in ROW_VARIANTS) {
                val raster = resize(rowRaster(original, row, slope.toDouble(), margin))
                observe(raster, fragment, variant, channels, cache, observations)
            }
            FragmentObservations(index, fragment, observations)
        }.also { checkpoint() }
    }

    /** Does not close a session, output or bitmap while the owning call is using it. */
    fun cancel() {
        synchronized(lifecycleLock) {
            cancelled = true
            try { activeRun?.setTerminate(true) } catch (_: OrtException) {
                // The flag still rejects the result; cancellation must not throw onStopped.
            }
        }
    }

    override fun close() {
        cancel()
        synchronized(ownerLock) {
            if (!closed) {
                closed = true
                try { session?.close() } finally { session = null; alphabet = emptyList() }
            }
        }
    }

    private fun checkpoint() {
        if (cancelled || closed || Thread.currentThread().isInterrupted) {
            throw CancellationException("Paddle OCR cancelled")
        }
    }

    private fun validate(original: Bitmap, count: Int, channels: List<Channel>) {
        checkpoint()
        require(!original.isRecycled && original.width > 0 && original.height > 0)
        require(count <= MAX_REGIONS) { "Too many Paddle regions: $count" }
        require(channels.isNotEmpty() && channels.size <= 2 && channels.distinct().size == channels.size)
    }

    private fun ensureSession(): OrtSession {
        checkpoint()
        session?.let { return it }
        val chars = JSONArray(String(readVerified("characters.json", DICTIONARY_SHA, 64 * 1024), Charsets.UTF_8))
        require(chars.length() == 837) { "Unexpected Paddle alphabet" }
        val loadedAlphabet = List(chars.length()) { chars.getString(it).also { value -> require(value.isNotEmpty()) } }
        val model = readVerified("inference.onnx", MODEL_SHA, 12 * 1024 * 1024)
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
            require(input.type == OnnxJavaType.FLOAT && input.shape.size == 4 && input.shape[1] == 3L && input.shape[2] == 48L)
            require(output.type == OnnxJavaType.FLOAT && output.shape.size == 3 && output.shape[2] == loadedAlphabet.size + 1L)
            alphabet = loadedAlphabet
            session = created
            return created
        } catch (error: Throwable) {
            created.close()
            throw error
        }
    }

    private fun readVerified(name: String, expectedSha: String, limit: Int): ByteArray {
        val bytes = assets.open("paddle/$name").use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(32 * 1024)
            while (true) {
                checkpoint()
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size().toLong() + count <= limit) { "Paddle asset exceeds limit" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        check(sha(bytes) == expectedSha) { "Paddle asset integrity failed: $name" }
        return bytes
    }

    private fun observe(
        raster: Raster, fragment: OcrLine, variant: Variant, channels: List<Channel>,
        cache: MutableMap<Pair<String, Channel>, Observation>, output: MutableList<Observation>,
    ) {
        checkpoint()
        val rasterId = rasterDigest(raster)
        for (channel in channels) {
            checkpoint()
            val key = rasterId to channel
            val observation = cache[key]?.copy(variant = variant) ?: infer(raster, fragment, variant, channel, rasterId)
                .also { cache[key] = it }
            output += observation
        }
    }

    private fun infer(raster: Raster, fragment: OcrLine, variant: Variant, channel: Channel, rasterId: String): Observation {
        val started = SystemClock.elapsedRealtime()
        checkpoint()
        val count = raster.width * raster.height
        val floats = ByteBuffer.allocateDirect(count * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val shifts = if (channel == Channel.BGR) intArrayOf(0, 8, 16) else intArrayOf(16, 8, 0)
        for (shift in shifts) for (index in raster.pixels.indices) {
            if (index % 8192 == 0) checkpoint()
            floats.put(((raster.pixels[index] ushr shift) and 255) / 127.5f - 1f)
        }
        floats.flip()
        val run = OrtSession.RunOptions()
        synchronized(lifecycleLock) {
            if (cancelled || closed || Thread.currentThread().isInterrupted) {
                run.close()
                throw CancellationException("Paddle OCR cancelled")
            }
            activeRun = run
        }
        try {
            OnnxTensor.createTensor(environment, floats, longArrayOf(1, 3, HEIGHT.toLong(), raster.width.toLong())).use { input ->
                ensureSession().run(mapOf("x" to input), run).use { result ->
                    checkpoint()
                    val tensor = result[0] as? OnnxTensor ?: error("Paddle output is not a tensor")
                    val shape = tensor.info.shape
                    require(shape.size == 3 && shape[0] == 1L && shape[1] in 1L..MAX_TIMESTEPS && shape[2] == alphabet.size + 1L)
                    val values = tensor.floatBuffer
                    val classes = shape[2].toInt()
                    require(values.remaining().toLong() == shape[1] * shape[2])
                    val characters = mutableListOf<CharacterObservation>()
                    var previous = -1
                    for (step in 0 until shape[1].toInt()) {
                        checkpoint()
                        var best = 0
                        var score = Float.NEGATIVE_INFINITY
                        for (index in 0 until classes) {
                            val value = values.get()
                            require(value.isFinite()) { "Invalid Paddle probability" }
                            if (value > score) { best = index; score = value }
                        }
                        require(score in 0f..1f) { "Unexpected Paddle score" }
                        if (best != 0 && best != previous) {
                            require(characters.size < MAX_CHARACTERS) { "Paddle text exceeds limit" }
                            characters += CharacterObservation(alphabet[best - 1], score, step, best)
                        }
                        previous = best
                    }
                    val text = characters.joinToString("") { it.text }
                    val confidence = if (characters.isEmpty()) 0f else characters.map { it.confidence.toDouble() }.average().toFloat()
                    checkpoint()
                    return Observation(variant, channel, text, confidence,
                        fragment.copy(text = text, confidence = confidence), characters, raster.width,
                        SystemClock.elapsedRealtime() - started, rasterId)
                }
            }
        } catch (error: OrtException) {
            if (cancelled || Thread.currentThread().isInterrupted) {
                throw CancellationException("Paddle OCR cancelled").apply { initCause(error) }
            }
            throw error
        } finally {
            synchronized(lifecycleLock) {
                activeRun = null
                run.close()
            }
        }
    }

    private data class Raster(val width: Int, val height: Int, val pixels: IntArray)

    private fun fragmentRaster(original: Bitmap, line: OcrLine, variant: Variant): Raster {
        checkpoint()
        val boxHeight = (line.bottom.toDouble() - line.top) * original.height
        val margin = if (variant == Variant.TIGHT) 0.0 else max(2.0, boxHeight * .12)
        val wide = variant == Variant.WIDE_HORIZONTAL || variant == Variant.WIDE_HORIZONTAL_DESKEW
        val horizontal = if (wide) max(2.0, boxHeight * .5) else margin
        val left = floor(line.left.toDouble() * original.width - horizontal).toInt().coerceIn(0, original.width - 1)
        val top = floor(line.top.toDouble() * original.height - margin).toInt().coerceIn(0, original.height - 1)
        val right = ceil(line.right.toDouble() * original.width + horizontal).toInt().coerceIn(left + 1, original.width)
        val bottom = ceil(line.bottom.toDouble() * original.height + margin).toInt().coerceIn(top + 1, original.height)
        val crop = readRaster(original, left, top, right - left, bottom - top)
        if (variant != Variant.DESKEW_MARGIN && variant != Variant.WIDE_HORIZONTAL_DESKEW) return crop
        val angle = atan(line.slope.toDouble() * original.height / original.width)
        if (abs(angle) < 1e-12) return crop
        val cosine = cos(angle)
        val sine = sin(angle)
        val cx = crop.width / 2.0
        val cy = crop.height / 2.0
        return warp(crop.width, crop.height) { x, y ->
            cubic(crop, cosine * (x - cx) - sine * (y - cy) + cx,
                sine * (x - cx) + cosine * (y - cy) + cy)
        }
    }

    private fun weightedSlope(row: List<OcrLine>): Float {
        var weighted = 0.0
        var weights = 0.0
        for (line in row) {
            val width = line.right.toDouble() - line.left
            val weight = width * width
            weights += weight
            weighted += line.slope * weight
        }
        return (weighted / weights).toFloat()
    }

    private fun rowRaster(original: Bitmap, row: List<OcrLine>, slope: Double, margin: Double): Raster {
        val width = original.width
        val height = original.height
        val pixelSlope = slope * height / width
        var left = row.minOf { it.left.toDouble() } * width
        var right = row.maxOf { it.right.toDouble() } * width
        val cx = (left + right) / 2
        var weightedCenter = 0.0
        var weights = 0.0
        var glyphHeight = 0.0
        for (line in row) {
            val span = line.right.toDouble() - line.left
            val weight = span * span
            val projected = ((line.top.toDouble() + line.bottom) / 2 + slope * (cx / width - (line.left.toDouble() + line.right) / 2)) * height
            weightedCenter += projected * weight
            weights += weight
            val boxHeight = (line.bottom.toDouble() - line.top) * height
            glyphHeight = max(glyphHeight, max(boxHeight - abs(pixelSlope) * span * width, .4 * boxHeight))
        }
        val cy = weightedCenter / weights
        left = max(0.0, left - margin * glyphHeight)
        right = minOf(width.toDouble(), right + margin * glyphHeight)
        val halfHeight = glyphHeight * .62 + 1
        val yLeft = cy + pixelSlope * (left - cx)
        val yRight = cy + pixelSlope * (right - cx)
        val outWidth = max(1, ceil(right - left).toInt())
        val outHeight = max(1, ceil(2 * halfHeight).toInt())
        val regionLeft = (floor(left).toInt() - 2).coerceIn(0, width - 1)
        val regionTop = (floor(minOf(yLeft, yRight) - halfHeight).toInt() - 2).coerceIn(0, height - 1)
        val regionRight = (ceil(right).toInt() + 3).coerceIn(regionLeft + 1, width)
        val regionBottom = (ceil(maxOf(yLeft, yRight) + halfHeight).toInt() + 3).coerceIn(regionTop + 1, height)
        val source = readRaster(original, regionLeft, regionTop, regionRight - regionLeft, regionBottom - regionTop)
        // The four source corners form a parallelogram: inverse perspective
        // mapping is affine and can be evaluated without allocating a full page.
        return warp(outWidth, outHeight) { x, y ->
            val u = x / (outWidth - 1).coerceAtLeast(1).toDouble()
            val v = y / (outHeight - 1).coerceAtLeast(1).toDouble()
            cubic(source, left + u * (right - left) - regionLeft,
                yLeft - halfHeight + u * (yRight - yLeft) + v * 2 * halfHeight - regionTop)
        }
    }

    private fun readRaster(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): Raster {
        checkpoint()
        boundedPixels(width, height)
        return Raster(width, height, IntArray(width * height).also { bitmap.getPixels(it, 0, width, left, top, width, height) })
    }

    private fun boundedPixels(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_CROP_PIXELS) { "Paddle crop exceeds limit" }
    }

    private inline fun warp(width: Int, height: Int, sample: (Int, Int) -> Int): Raster {
        boundedPixels(width, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            checkpoint()
            for (x in 0 until width) pixels[y * width + x] = sample(x, y)
        }
        return Raster(width, height, pixels)
    }

    /** OpenCV's cubic kernel (A=-.75), 1/32 subpixel table, replicated borders. */
    private fun cubic(source: Raster, x: Double, y: Double): Int {
        val quantizedX = Math.rint(x * 32) / 32
        val quantizedY = Math.rint(y * 32) / 32
        val left = floor(quantizedX).toInt()
        val top = floor(quantizedY).toInt()
        val wx = CUBIC_WEIGHTS[((quantizedX - left) * 32).toInt().coerceIn(0, 31)]
        val wy = CUBIC_WEIGHTS[((quantizedY - top) * 32).toInt().coerceIn(0, 31)]
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        for (yy in 0..3) {
            val offset = (top + yy - 1).coerceIn(0, source.height - 1) * source.width
            for (xx in 0..3) {
                val color = source.pixels[offset + (left + xx - 1).coerceIn(0, source.width - 1)]
                val weight = wx[xx] * wy[yy]
                red += ((color ushr 16) and 255) * weight
                green += ((color ushr 8) and 255) * weight
                blue += (color and 255) * weight
            }
        }
        return color(red, green, blue)
    }

    /** Half-pixel bilinear resize to uint8 before normalization, as in Paddle. */
    private fun resize(source: Raster): Raster {
        val width = ceil(HEIGHT.toDouble() * source.width / source.height).toInt().coerceIn(1, MAX_WIDTH)
        val resized = if (source.height == HEIGHT && source.width == width) source else warp(width, HEIGHT) { x, y ->
            val sx = ((x + .5) * source.width / width - .5).coerceIn(0.0, source.width - 1.0)
            val sy = ((y + .5) * source.height / HEIGHT - .5).coerceIn(0.0, source.height - 1.0)
            val left = floor(sx).toInt()
            val top = floor(sy).toInt()
            val dx = sx - left
            val dy = sy - top
            val a = source.pixels[top * source.width + left]
            val b = source.pixels[top * source.width + minOf(left + 1, source.width - 1)]
            val c = source.pixels[minOf(top + 1, source.height - 1) * source.width + left]
            val d = source.pixels[minOf(top + 1, source.height - 1) * source.width + minOf(left + 1, source.width - 1)]
            fun value(shift: Int): Double {
                val upper = ((a ushr shift) and 255) * (1 - dx) + ((b ushr shift) and 255) * dx
                val lower = ((c ushr shift) and 255) * (1 - dx) + ((d ushr shift) and 255) * dx
                return upper * (1 - dy) + lower * dy
            }
            color(value(16), value(8), value(0))
        }
        if (width >= MIN_WIDTH) return resized
        // ORT1.20 ARM can reduce an extremely narrow crop to a zero-width
        // intermediate convolution. Keep the proportional glyph pixels and
        // append white columns; never widen the glyph to satisfy the model.
        val padded = IntArray(MIN_WIDTH * HEIGHT) { -1 }
        for (y in 0 until HEIGHT) {
            checkpoint()
            resized.pixels.copyInto(padded, y * MIN_WIDTH, y * width, (y + 1) * width)
        }
        return Raster(MIN_WIDTH, HEIGHT, padded)
    }

    private fun color(red: Double, green: Double, blue: Double): Int = (255 shl 24) or
        (floor(red + .5).toInt().coerceIn(0, 255) shl 16) or
        (floor(green + .5).toInt().coerceIn(0, 255) shl 8) or floor(blue + .5).toInt().coerceIn(0, 255)

    private fun rasterDigest(raster: Raster): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(raster.width).putInt(raster.height).array())
        val row = ByteArray(raster.width * 3)
        for (y in 0 until raster.height) {
            checkpoint()
            for (x in 0 until raster.width) {
                val color = raster.pixels[y * raster.width + x]
                row[x * 3] = (color ushr 16).toByte()
                row[x * 3 + 1] = (color ushr 8).toByte()
                row[x * 3 + 2] = color.toByte()
            }
            digest.update(row)
        }
        return hex(digest.digest())
    }

    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val MODEL_SHA = "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498"
        const val DICTIONARY_SHA = "c0c61a12c423fd5874bb6acaf8323c75a93de83d4541ae7f7e70554d572d3048"
        const val RUNTIME_VERSION = "1.20.0"
        private const val HEIGHT = 48
        private const val MIN_WIDTH = 16
        private const val MAX_WIDTH = 3200
        private const val MAX_REGIONS = 128
        private const val MAX_ROW_FRAGMENTS = 1024L
        private const val MAX_CROP_PIXELS = 4_000_000L
        private const val MAX_TIMESTEPS = 4096L
        private const val MAX_CHARACTERS = 1000
        private val CUBIC_WEIGHTS = Array(32) { cubicWeights(it / 32.0) }
        private fun cubicWeights(t: Double): DoubleArray {
            val a = -.75
            val first = ((a * (t + 1) - 5 * a) * (t + 1) + 8 * a) * (t + 1) - 4 * a
            val second = ((a + 2) * t - (a + 3)) * t * t + 1
            val inverse = 1 - t
            val third = ((a + 2) * inverse - (a + 3)) * inverse * inverse + 1
            return doubleArrayOf(first, second, third, 1 - first - second - third)
        }

        private val FRAGMENT_VARIANTS = listOf(Variant.TIGHT, Variant.MARGIN, Variant.DESKEW_MARGIN,
            Variant.WIDE_HORIZONTAL_DESKEW, Variant.WIDE_HORIZONTAL)
        private val ROW_VARIANTS = listOf(Variant.ROW_MARGIN_HALF to .5, Variant.ROW_MARGIN_ONE to 1.0, Variant.ROW_MARGIN_TWO to 2.0)
    }
}

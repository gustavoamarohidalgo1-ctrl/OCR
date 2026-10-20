package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Extra optical observations of uncertain rows, using only the imported pixels. */
internal class RegionalOcrVerifier(
    private val engine: OfflineOcr,
    private val independent: (() -> List<List<OcrLine>>)? = null,
    /**
     * Asked once, as soon as this verifier can no longer confirm every row, with the
     * evidence gathered so far. True means no later route can accept the page either, so
     * the remaining uncertain rows stay unresolved without further reads.
     */
    private val abandon: ((Result) -> Boolean)? = null,
) {
    data class RowEvidence(val whole: List<List<OcrLine>>, val regional: List<List<OcrLine>>,
        val selected: List<OcrLine>, val unresolved: Boolean)
    data class Result(val lines: List<OcrLine>, val unresolved: Int, val recovered: Int,
        val rows: List<RowEvidence> = emptyList(), val wordReadings: List<List<OcrLine>> = emptyList(),
        val unassignedWholeText: Boolean = false, val contextMappingIncomplete: Boolean = false)

    fun verify(bitmap: Bitmap, first: OfflineOcr.Reading, second: OfflineOcr.Reading): Result {
        val enlarged = prepare(bitmap, 0, bitmap.height, Mode.CONTRAST, false)
        val third = readOwned(enlarged) { engine.recognizeRegion(it) }
        if (third.lines.isEmpty()) return Result(first.lines, 1, 0)
        // Full-frame scaling preserves normalized coordinates and baseline slopes.
        val readings = listOf(first.lines, second.lines, third.lines)
        val anchors = OcrLayout.rows(first.lines).map { it.toMutableList() }.toMutableList()
        for (reading in readings.drop(1)) {
            for (row in OcrLayout.rows(reading)) {
                if (row.none { owner(it, anchors) >= 0 }) anchors += row.toMutableList()
            }
        }
        anchors.sortBy { row -> row.map { (it.top + it.bottom) / 2 }.average() }
        if (anchors.isEmpty() || anchors.size > 160) return Result(first.lines, 1, 0)
        val owners = readings.map { reading -> reading.map { owner(it, anchors) } }
        val assigned = readings.mapIndexed { readingIndex, reading ->
            List(anchors.size) { mutableListOf<OcrLine>() }.also { buckets ->
                reading.forEachIndexed { fragmentIndex, line ->
                    val index = owners[readingIndex][fragmentIndex]
                    if (index >= 0) buckets[index] += line
                }
            }
        }
        val unresolvedRows = mutableSetOf<Int>()
        val regionalEvidence = List(anchors.size) { mutableListOf<List<OcrLine>>() }
        val wordReadings = mutableListOf(first.words, second.words, third.words)
        fun complete(reading: OfflineOcr.Reading) = reading.suggestedRotation == 0 &&
            ReadingInventory.matches(reading.text, reading.lines.map { it.text }, reading.words.map { it.text })
        var contextMappingIncomplete = !complete(third)
        var extraObservations = 0
        var regionObservations = 0
        val independentObservations = lazy { independent?.invoke().orEmpty() }
        val independentReadings by independentObservations
        var recovered = 0
        val output = mutableListOf<OcrLine>()
        val finalRows = mutableListOf<List<OcrLine>>()
        val unassigned = owners.any { reading -> reading.any { it < 0 } }
        val abandoned = lazy {
            abandon?.invoke(Result(output.toList(), unresolvedRows.size + if (unassigned) 1 else 0, recovered,
                anchors.indices.map { index ->
                    RowEvidence(assigned.map { it[index] }, regionalEvidence[index].toList(),
                        finalRows.getOrElse(index) { emptyList() }, index in unresolvedRows)
                }, wordReadings.toList(), unassigned, contextMappingIncomplete)) == true
        }
        for (index in anchors.indices) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Escaneo interrumpido.")
            val observations = assigned.map { it[index] }
            val stable = RegionConsensus.whole(observations)
            if (stable != null) { output += stable; finalRows += stable; continue }
            // Without a complete confirmation this page needs another route; when none
            // remains, rereading the other rows cannot change it and only costs time.
            if ((unassigned || unresolvedRows.isNotEmpty()) && abandoned.value ||
                regionObservations + 5 > MAX_REGION_OBSERVATIONS) {
                unresolvedRows += index
                val fallback = observations.firstOrNull { it.isNotEmpty() }.orEmpty()
                output += fallback
                finalRows += fallback
                continue
            }
            val regional = regionalEvidence[index]
            val observedRasters = mutableSetOf<String>()
            for ((context, mode) in listOf(1 to Mode.GRAY, 1 to Mode.CONTRAST,
                3 to Mode.COLOR, 3 to Mode.GRAY, 3 to Mode.CONTRAST)) {
                val included = anchors.subList((index - context / 2).coerceAtLeast(0),
                    (index + context / 2 + 1).coerceAtMost(anchors.size)).flatten()
                val heights = included.map { (it.bottom - it.top) * bitmap.height }.sorted()
                val margin = maxOf(8f, heights[heights.size / 2] * .75f)
                val top = floor(included.minOf { it.top } * bitmap.height - margin).toInt().coerceAtLeast(0)
                val bottom = ceil(included.maxOf { it.bottom } * bitmap.height + margin).toInt().coerceAtMost(bitmap.height)
                val prepared = prepare(bitmap, top, bottom, mode, true)
                if (!distinctRaster(prepared, observedRasters)) continue
                val width = prepared.width
                val height = prepared.height
                val sx = width.toFloat() / (bitmap.width + 2 * PADDING)
                val sy = height.toFloat() / (bottom - top + 2 * PADDING)
                val reading = readOwned(prepared) { engine.recognizeRegion(it) }
                fun mapLine(line: OcrLine): OcrLine? {
                    val left = ((line.left * width / sx - PADDING) / bitmap.width).coerceIn(0f, 1f)
                    val right = ((line.right * width / sx - PADDING) / bitmap.width).coerceIn(0f, 1f)
                    val lineTop = ((line.top * height / sy - PADDING + top) / bitmap.height).coerceIn(0f, 1f)
                    val lineBottom = ((line.bottom * height / sy - PADDING + top) / bitmap.height).coerceIn(0f, 1f)
                    return if (right <= left || lineBottom <= lineTop) null else {
                        val slope = line.slope * (height / sy / bitmap.height) / (width / sx / bitmap.width)
                        line.copy(left = left, right = right, top = lineTop, bottom = lineBottom, slope = slope)
                    }
                }
                val mapped = reading.lines.mapNotNull(::mapLine).filter { owner(it, anchors) == index }
                val mappedWords = reading.words.map(::mapLine)
                if (!complete(reading) || mappedWords.any { it == null }) contextMappingIncomplete = true
                wordReadings += mappedWords.filterNotNull()
                regional += mapped
                regionObservations++
                // Require all planned observations before deciding: a later contradictory
                // crop must not be hidden by returning after the first agreeing pair.
            }
            var resolved = RegionConsensus.recovered(observations, regional)
            if (resolved == null && extraObservations + EXTRA_VARIANTS.size <= MAX_EXTRA_OBSERVATIONS &&
                regionObservations + EXTRA_VARIANTS.size <= MAX_REGION_OBSERVATIONS) {
                // Wider context, horizontal isolation and deskew are observations of
                // the same pixels. Their inverse mapping keeps amounts in source rows.
                for (spec in EXTRA_VARIANTS) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Escaneo interrumpido.")
                    val start = (index - (spec.context - 1) / 2).coerceAtLeast(0)
                    val end = (start + spec.context).coerceAtMost(anchors.size)
                    val included = anchors.subList(start, end).flatten()
                    val prepared = RegionRaster.prepare(bitmap, included, spec.scale, spec.deskew, spec.contrast)
                    if (!distinctRaster(prepared.bitmap, observedRasters)) continue
                    var interrupted = false
                    try {
                        val reading = engine.recognizeRegion(prepared.bitmap)
                        regional += reading.lines.mapNotNull { RegionRaster.map(it, prepared, bitmap.width, bitmap.height) }
                            .filter { owner(it, anchors) == index }
                        val mappedWords = reading.words.map { RegionRaster.map(it, prepared, bitmap.width, bitmap.height) }
                        if (!complete(reading) || mappedWords.any { it == null }) contextMappingIncomplete = true
                        wordReadings += mappedWords.filterNotNull()
                    } catch (error: InterruptedException) { interrupted = true; throw error }
                    finally { if (!interrupted && !prepared.bitmap.isRecycled) prepared.bitmap.recycle() }
                    extraObservations++
                    regionObservations++
                }
                resolved = RegionConsensus.recovered(observations, regional)
            }
            if (resolved == null && independent != null && BoundaryConsensus.canAttempt(observations, regional)) {
                resolved = BoundaryConsensus.recovered(observations, regional, independentReadings)
            }
            if (resolved == null) {
                unresolvedRows += index
                val fallback = observations.firstOrNull { it.isNotEmpty() }.orEmpty()
                output += fallback
                finalRows += fallback
            } else { output += resolved; finalRows += resolved; recovered++ }
        }
        // Unassigned whole-image text is never silently dropped from a successful PDF.
        if (unassigned) unresolvedRows += anchors.size
        // Once the independent reader is consulted, a boundary disagreement in
        // another apparently stable row must not be hidden by the local rescue.
        if (independentObservations.isInitialized()) {
            finalRows.forEachIndexed { index, row ->
                if (BoundaryConsensus.hasBoundaryDisagreement(row, independentReadings)) unresolvedRows += index
            }
        }
        return Result(output, unresolvedRows.size, recovered, anchors.indices.map { index ->
            RowEvidence(assigned.map { it[index] }, regionalEvidence[index].toList(), finalRows[index], index in unresolvedRows)
        }, wordReadings, unassigned, contextMappingIncomplete)
    }

    private fun distinctRaster(bitmap: Bitmap, observed: MutableSet<String>): Boolean {
        var keep = false
        try { keep = observed.add(rasterIdentity(bitmap)); return keep }
        finally { if (!keep && !bitmap.isRecycled) bitmap.recycle() }
    }

    /** Identical transformed pixels count once, even if two recipes happen to coincide. */
    private fun rasterIdentity(bitmap: Bitmap): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width * 16)
        val buffer = java.nio.ByteBuffer.allocate(pixels.size * 4)
        digest.update(java.nio.ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
        var top = 0
        while (top < bitmap.height) {
            val rows = minOf(16, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            buffer.clear()
            buffer.asIntBuffer().put(pixels, 0, bitmap.width * rows)
            digest.update(buffer.array(), 0, bitmap.width * rows * 4)
            top += rows
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun owner(line: OcrLine, rows: List<List<OcrLine>>): Int {
        val closest = rows.indices.minByOrNull { OcrLayout.rowDistance(line, rows[it]) } ?: return -1
        val row = rows[closest]
        val heights = row.map { it.bottom - it.top }.sorted()
        val limit = maxOf(line.bottom - line.top, heights[heights.size / 2]) * .9f
        return if (OcrLayout.rowDistance(line, row) <= limit) closest else -1
    }

    private fun readOwned(bitmap: Bitmap, read: (Bitmap) -> OfflineOcr.Reading): OfflineOcr.Reading {
        var interrupted = false
        try { return read(bitmap) }
        catch (error: InterruptedException) { interrupted = true; throw error }
        finally { if (!interrupted && !bitmap.isRecycled) bitmap.recycle() }
    }

    private enum class Mode { COLOR, GRAY, CONTRAST }

    /** Copies into a bounded temporary raster. The source and imported file are untouched. */
    private fun prepare(source: Bitmap, top: Int, bottom: Int, mode: Mode, pad: Boolean): Bitmap {
        val padding = if (pad) PADDING else 0
        val width = source.width + 2 * padding
        val height = bottom - top + 2 * padding
        val factor = minOf(2.0, sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height)))
        val native = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (mode != Mode.COLOR) {
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                if (mode == Mode.CONTRAST) {
                    val c = 1.18f
                    val offset = 128f * (1 - c)
                    matrix.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,offset, 0f,c,0f,0f,offset,
                        0f,0f,c,0f,offset, 0f,0f,0f,1f,0f)))
                }
                paint.colorFilter = ColorMatrixColorFilter(matrix)
            }
            Canvas(native).apply {
                drawColor(Color.WHITE)
                clipRect(padding, padding, padding + source.width, padding + bottom - top)
                drawBitmap(source, padding.toFloat(), padding.toFloat() - top, paint)
            }
            val output = Bitmap.createScaledBitmap(native, (width * factor).toInt().coerceAtLeast(1),
                (height * factor).toInt().coerceAtLeast(1), true)
            if (output !== native) native.recycle()
            output.density = Bitmap.DENSITY_NONE
            return output
        } catch (error: Throwable) { if (!native.isRecycled) native.recycle(); throw error }
    }

    private data class Variant(val context: Int, val scale: Double, val deskew: Boolean, val contrast: Boolean = false)
    companion object {
        private const val PADDING = 24
        private const val MAX_PIXELS = 4_000_000L
        private const val MAX_EXTRA_OBSERVATIONS = 48
        private const val MAX_REGION_OBSERVATIONS = 96
        // Run the full set before selection so later contradictory readings count.
        private val EXTRA_VARIANTS = listOf(Variant(2, 2.0, false), Variant(3, 3.0, false),
            Variant(3, 2.0, true), Variant(3, 3.0, true, true))
    }
}

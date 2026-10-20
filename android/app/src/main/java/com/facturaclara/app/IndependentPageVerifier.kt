package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/** Complete-page recovery with cells proposed from pixels by an independent detector. */
internal class IndependentPageVerifier(
    private val ml: OfflineOcr,
    private val paddle: PaddleOcr,
    private val detector: PaddleTextDetector,
    private val tess: IndependentOcr,
) {
    data class Result(val lines: List<OcrLine>, val unresolved: Int,
        val evidence: List<FallbackEvidenceGuard.CellEvidence> = emptyList(),
        val fallbackBlocked: Boolean = false)

    fun verify(original: Bitmap, primary: List<OfflineOcr.Reading>, trace: File): Result {
        val started = SystemClock.elapsedRealtime()
        require(primary.isNotEmpty())
        // A missing word box must never turn a known printed token into absent evidence.
        if (primary.any { reading -> reading.words.size > MAX_WORDS ||
            !ReadingInventory.matches(reading.text, reading.lines.map { it.text }, reading.words.map { it.text })
        }) return Result(emptyList(), 1, fallbackBlocked = true)
        checkpoint()
        val detected = detector.detect(original)
        val plan = IndependentCellPlan.build(detected.detections.map { it.line }, primary.map { it.words },
            original.width, original.height)
        val regions = plan.cells.map { cell ->
            cell.detectorIndex?.let { detected.detections[it] } ?: cell.line.let { box ->
                PaddleTextDetector.Detection(listOf(PointF(box.left, box.top), PointF(box.right, box.top),
                    PointF(box.right, box.bottom), PointF(box.left, box.bottom)), 0f, box, rotateVertical = false)
            }
        }
        val cells = regions.map { it.line }
        val clippedSourceWords = mutableListOf<IndependentCellPlan.WordOccurrence>()
        for ((pass, reading) in primary.withIndex()) for ((wordIndex, word) in reading.words.withIndex()) {
            val owners = OpticalCellAssignment.assign(listOf(word), cells, original.width, original.height)
                .mapIndexedNotNull { owner, words -> if (words.isNotEmpty()) owner else null }
            if (owners.size != 1 || !detector.coversSourceWord(original, regions[owners.single()], word, .08f)) {
                clippedSourceWords += IndependentCellPlan.WordOccurrence(pass, wordIndex, word)
            }
        }
        val physicalCoverageBlocker = if (plan.uncovered.isEmpty() && clippedSourceWords.isEmpty()) 0 else 1
        if (cells.isEmpty() || cells.size > MAX_CELLS) {
            trace.writeText(JSONObject().put("reference_used", false).put("source_modified", false)
                .put("detected_cells", cells.size).put("unresolved", 1)
                .put("reason", "empty_or_excessive_complete_page").toString(2))
            return Result(emptyList(), 1)
        }
        val observations = cells.map { mutableListOf<OpticalCellConsensus.Observation>() }
        val raw = JSONArray()
        regions.forEachIndexed { index, detection ->
            for (margin in listOf(0f, .08f)) {
                checkpoint()
                val crop = detector.rectify(original, detection, margin)
                try {
                    val observation = paddle.recognizeRectified(crop, cells[index])
                    observations[index] += OpticalCellConsensus.Observation(
                        text = observation.text, confidence = observation.confidence,
                        sourceId = observation.rasterId, family = OpticalCellConsensus.Family.PADDLE,
                        minimumCharacterConfidence = observation.characters.minOfOrNull { it.confidence })
                    raw.put(JSONObject().put("index", index).put("margin", margin.toDouble())
                        .put("text", observation.text).put("confidence", observation.confidence.toDouble())
                        .put("minimum_character_confidence", observation.characters.minOfOrNull { it.confidence }?.toDouble())
                        .put("raster_id", observation.rasterId))
                } finally { crop.recycle() }
            }
        }
        val primaryWords = primary.map { it.words }
        val extra = cells.map { mutableListOf<OpticalCellConsensus.Observation>() }
        val geometryBlocked = mutableSetOf<Int>()
        val rejectedContext = JSONArray()
        fun ownsAll(index: Int, words: List<OcrLine>, family: String, pass: Int, margin: Float): Boolean {
            val mapped = words.map { word ->
                detector.mapFromRectified(original, regions[index], word, margin)
            }
            val valid = mapped.all { word ->
                word != null && OpticalCellAssignment.assign(listOf(word), cells, original.width, original.height)
                    .mapIndexedNotNull { owner, entries -> if (entries.isNotEmpty()) owner else null } == listOf(index)
            }
            if (!valid) {
                geometryBlocked += index
                rejectedContext.put(JSONObject().put("index", index).put("family", family).put("pass", pass)
                    .put("margin", margin.toDouble())
                    .put("reason", "crop_word_has_missing_or_ambiguous_owner")
                    .put("words", JSONArray(words.map { it.text })))
            }
            return valid
        }
        fun evaluate() = IndependentPageConsensus.evaluate(cells, observations, primaryWords,
            emptyList(), original.width, original.height, extra)
        val initial = evaluate()
        val firstCompleted = SystemClock.elapsedRealtime()
        val uncertain = initial.decisions.indices.filter { initial.decisions[it].text == null }
        // Each stage fixes its complete observation set before reading it. Existing
        // rival observations remain evidence when another raster is consulted.
        fun readContext(index: Int, margin: Float, illumination: Boolean) {
            checkpoint()
            val rawCrop = detector.rectify(original, regions[index], margin)
            val prepared = if (illumination) try { LineIllumination.normalize(rawCrop) }
                finally { rawCrop.recycle() } else rawCrop
            val crop = try {
                if (illumination) {
                    val optical = paddle.recognizeRectified(prepared, cells[index])
                    observations[index] += OpticalCellConsensus.Observation(optical.text, optical.confidence,
                        optical.rasterId, OpticalCellConsensus.Family.PADDLE,
                        optical.characters.minOfOrNull { it.confidence })
                    raw.put(JSONObject().put("index", index).put("margin", margin.toDouble())
                        .put("illumination_normalized", true).put("text", optical.text)
                        .put("confidence", optical.confidence.toDouble())
                        .put("minimum_character_confidence", optical.characters.minOfOrNull { it.confidence }?.toDouble())
                        .put("raster_id", optical.rasterId))
                }
                contextSize(prepared)
            } finally { prepared.recycle() }
            var pendingMl = false
            try {
                val reading = try { ml.recognizeRegion(crop) }
                catch (error: InterruptedException) { pendingMl = true; throw error }
                val words = reading.words.sortedBy { it.left }
                val inventoryMatches = ReadingInventory.matches(reading.text,
                    reading.lines.map { it.text }, words.map { it.text })
                if (!inventoryMatches || reading.suggestedRotation != 0) geometryBlocked += index
                if (reading.suggestedRotation == 0 && inventoryMatches && words.isNotEmpty() &&
                    ownsAll(index, words, "ML", 0, margin)) {
                    extra[index] += OpticalCellConsensus.Observation(reading.text,
                        words.minOf { it.confidence ?: 0f }, "rectified-ml-$index-$margin-$illumination",
                        OpticalCellConsensus.Family.ML,
                        wordConfidences = words.map { OpticalCellConsensus.WordConfidence(it.text, it.confidence ?: 0f) })
                }
                for ((pass, tessWords) in tess.recognizeLine(crop).withIndex()) {
                    if (tessWords.isNotEmpty() && ownsAll(index, tessWords, "TESSERACT", pass, margin)) {
                        extra[index] += OpticalCellConsensus.Observation(
                            tessWords.joinToString(" ") { it.text }, tessWords.minOf { it.confidence ?: 0f },
                            "rectified-tess-$index-$margin-$illumination-$pass", OpticalCellConsensus.Family.TESSERACT)
                    }
                }
            } finally { if (!pendingMl && !crop.isRecycled) crop.recycle() }
        }
        if (uncertain.size <= MAX_CONTEXT_CELLS) {
            for (index in uncertain) for (margin in listOf(0f, .08f)) readContext(index, margin, false)
        }
        val afterContext = evaluate()
        val shadowCells = afterContext.decisions.indices.filter { afterContext.decisions[it].text == null }
        if (shadowCells.size <= MAX_CONTEXT_CELLS) {
            for (index in shadowCells) for (margin in listOf(0f, .08f)) readContext(index, margin, true)
        }
        val result = evaluate()
        val report = JSONObject().put("reference_used", false).put("source_modified", false)
            .put("geometry_source", "independent_full_page_pixels")
            .put("detector_model_sha256", detected.modelSha)
            .put("detector_profile", detected.profile.name)
            .put("detector_settings", JSONObject(detected.settings))
            .put("detector_elapsed_ms", detected.elapsedMs)
            .put("detector_input_width", detected.inputWidth).put("detector_input_height", detected.inputHeight)
            .put("detector_contours", detected.contourCount)
            .put("initial_unresolved", initial.unresolved)
            .put("first_read_elapsed_ms", firstCompleted - started)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("context_cells", JSONArray(if (uncertain.size <= MAX_CONTEXT_CELLS) uncertain else emptyList<Int>()))
            .put("unresolved", result.unresolved + geometryBlocked.size + physicalCoverageBlocker).put("raw_recognition", raw)
            .put("uncovered_source_locations", JSONArray(plan.uncovered.map { occurrence ->
                JSONObject().put("reading", occurrence.readingIndex).put("word_index", occurrence.wordIndex)
                    .put("text", occurrence.word.text)
            }))
            .put("incomplete_source_word_crops", JSONArray(clippedSourceWords.map { occurrence ->
                JSONObject().put("reading", occurrence.readingIndex).put("word_index", occurrence.wordIndex)
                    .put("text", occurrence.word.text)
            }))
            .put("shadow_cells", JSONArray(if (shadowCells.size <= MAX_CONTEXT_CELLS) shadowCells else emptyList<Int>()))
            .put("context_geometry_blocked", JSONArray(geometryBlocked.sorted()))
            .put("rejected_context", rejectedContext)
            .put("uncovered_primary_words", JSONArray(result.uncoveredMl.map { occurrence ->
                JSONObject().put("reading", occurrence.readingIndex).put("word_index", occurrence.wordIndex)
                    .put("text", occurrence.word.text)
            }))
            .put("cells", JSONArray(cells.indices.map { index ->
                val cell = cells[index]
                val decision = result.decisions[index]
                JSONObject().put("index", index).put("left", cell.left.toDouble()).put("top", cell.top.toDouble())
                    .put("right", cell.right.toDouble()).put("bottom", cell.bottom.toDouble())
                    .put("slope", cell.slope.toDouble()).put("selected", decision.text ?: JSONObject.NULL)
                    .put("blockers", JSONArray(decision.blockers))
                    .put("families", JSONArray(decision.supportingFamilies.map { it.name }))
                    .put("geometry_source", plan.cells[index].source.name)
                    .put("detector_confidence", if (plan.cells[index].detectorIndex != null) regions[index].confidence.toDouble() else JSONObject.NULL)
                    .put("polygon", JSONArray(regions[index].points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
                    .put("supplementary_occurrences", JSONArray(plan.cells[index].occurrences.map { occurrence ->
                        JSONObject().put("reading", occurrence.readingIndex).put("word_index", occurrence.wordIndex)
                            .put("left", occurrence.word.left.toDouble()).put("top", occurrence.word.top.toDouble())
                            .put("right", occurrence.word.right.toDouble()).put("bottom", occurrence.word.bottom.toDouble())
                    }))
                    .put("whole_ml", JSONArray(result.assignedMl.map { pass -> JSONArray(pass[index].map { word ->
                        JSONObject().put("text", word.text).put("confidence", word.confidence?.toDouble())
                    }) }))
                    .put("extra_observations", JSONArray(extra[index].map { observation ->
                        JSONObject().put("source_id", observation.sourceId).put("family", observation.family.name).put("text", observation.text)
                            .put("confidence", observation.confidence.toDouble())
                    }))
            }))
        checkpoint()
        trace.writeText(report.toString(2))
        val evidence = cells.indices.map { index ->
            val whole = result.assignedMl.mapIndexedNotNull { pass, assigned ->
                val words = assigned[index].sortedBy { it.left }
                if (words.isEmpty()) null else OpticalCellConsensus.Observation(
                    words.joinToString(" ") { it.text }, words.minOf { it.confidence ?: 0f },
                    "whole-ml-$pass", OpticalCellConsensus.Family.ML,
                    wordConfidences = words.map { OpticalCellConsensus.WordConfidence(it.text, it.confidence ?: 0f) })
            }
            FallbackEvidenceGuard.CellEvidence(cells[index], observations[index] + extra[index] + whole,
                result.decisions[index])
        }
        return Result(result.lines, result.unresolved + geometryBlocked.size + physicalCoverageBlocker,
            evidence, geometryBlocked.isNotEmpty() || physicalCoverageBlocker != 0)
    }

    private fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Lectura de página interrumpida.")
    }

    private fun contextSize(source: Bitmap): Bitmap {
        val scale = maxOf(1.0, 64.0 / source.height, 32.0 / source.width)
        val width = ceil(source.width * scale).toInt()
        val height = ceil(source.height * scale).toInt()
        require(width.toLong() * height <= 4_000_000L) { "Context exceeds complete crop budget" }
        // ML Kit requires both dimensions >=32. Keep the whole patch and its
        // aspect ratio; never stretch a digit to independently satisfy a side.
        return if (width == source.width && height == source.height) source.copy(Bitmap.Config.ARGB_8888, false)
            else Bitmap.createScaledBitmap(source, width, height, true)
    }

    companion object {
        private const val MAX_CELLS = 128
        private const val MAX_CONTEXT_CELLS = 24
        private const val MAX_WORDS = 1500
    }
}

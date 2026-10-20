package com.facturaclara.app

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All replacements are literal optical observations, corroborated by another model family. */
internal class CrossReaderVerifier(
    private val ml: OfflineOcr,
    private val paddle: PaddleOcr,
    private val tess: IndependentOcr,
) {
    data class Result(
        val lines: List<OcrLine>,
        val unresolved: Int,
        val evidence: List<FallbackEvidenceGuard.CellEvidence> = emptyList(),
        val fallbackBlocked: Boolean = false,
        /** Whole observations followed by every mapped regional/context observation. */
        val mlReadings: List<List<OcrLine>> = emptyList(),
        val tessReadings: List<List<OcrLine>> = emptyList(),
        val contextMappingIncomplete: Boolean = false,
    )

    /** Coverage of the complete-page readings, known before any cell or strip is read. */
    private class WholePage(
        val tokensMatch: Boolean,
        val uncovered: Boolean = false,
        val orphanWords: List<OcrLine> = emptyList(),
        val orphanObservations: List<PaddleOcr.FragmentObservations> = emptyList(),
        val boundary: DocumentBoundary.Detection? = null,
        val backgroundIndices: Set<Int> = emptySet(),
    ) {
        val uncoveredIndependent: Boolean get() = orphanWords.indices.any { it !in backgroundIndices }
    }
    private var wholePage: Pair<List<Any>, WholePage>? = null

    /** The same whole-page evidence is reused while its inputs are unchanged. */
    private fun wholePage(original: Bitmap, regional: RegionalOcrVerifier.Result, wholeTess: List<List<OcrLine>>): WholePage {
        val key = listOf(original.width, original.height, regional.rows.map { it.whole }, regional.wordReadings.take(3), wholeTess)
        wholePage?.let { (previous, page) -> if (previous == key) return page }
        val rows = regional.rows.map { row -> row.whole.firstOrNull { it.isNotEmpty() }.orEmpty() }
        val cells = rows.flatten()
        // Line text may contain an element whose detector box was absent. Never
        // let mapNotNull in the word adapter make that token disappear unnoticed.
        val tokensMatch = (0..2).all { pass ->
            val lineTokens = regional.rows.flatMap { it.whole[pass] }.flatMap { RegionConsensus.tokens(it.text) }.sorted()
            val wordTokens = regional.wordReadings[pass].flatMap { RegionConsensus.tokens(it.text) }.sorted()
            lineTokens == wordTokens
        }
        val page = if (!tokensMatch) WholePage(false) else {
            // Every reliable word from the complete ML passes must have an owner. Regional
            // strips are not whole-page coverage proofs; their words are checked in verify.
            val uncovered = regional.wordReadings.take(3).any { words ->
                val assigned = assign(words, cells, original.width, original.height).flatten().toSet()
                words.any { it.text.isNotBlank() && it !in assigned }
            }
            val orphanWords = wholeTess.flatMap { words ->
                val assigned = assign(words, cells, original.width, original.height).flatten().toSet()
                words.filter { (it.confidence ?: 0f) >= .85f && it !in assigned }
            }
            val orphanObservations = if (orphanWords.size <= 16) paddle.recognize(original, orphanWords) else emptyList()
            val boundary = if (orphanWords.isNotEmpty()) DocumentBoundary.detect(original,
                regional.rows.flatMap { it.whole.flatten() } + regional.wordReadings.take(3).flatten()) else null
            val backgroundIndices = orphanWords.indices.filter { index ->
                boundary?.definitelyOutside(orphanWords[index]) == true &&
                    orphanObservations.firstOrNull { it.index == index }?.observations?.let { readings ->
                        readings.isNotEmpty() && readings.none { it.text.isNotBlank() && it.confidence >= .95f &&
                            it.characters.isNotEmpty() && it.characters.all { character -> character.confidence >= .75f } }
                    } == true
            }.toSet()
            WholePage(true, uncovered, orphanWords, orphanObservations, boundary, backgroundIndices)
        }
        wholePage = key to page
        return page
    }

    /**
     * True when [verify] cannot accept the page nor open the later recovery route, whatever
     * its cell and strip reads show: whole-page text lacks an owner cell (Coverage.blocked)
     * or the regional mapping is already incomplete. Regional context can only add blocks.
     */
    fun closedBeforeCellReads(original: Bitmap, regional: RegionalOcrVerifier.Result, wholeTess: List<List<OcrLine>>): Boolean {
        if (!canAttempt(regional)) return true
        val page = wholePage(original, regional, wholeTess)
        return !page.tokensMatch || page.uncovered || page.uncoveredIndependent || regional.contextMappingIncomplete
    }

    fun verify(original: Bitmap, regional: RegionalOcrVerifier.Result,
        wholeTess: List<List<OcrLine>>, trace: File): Result {
        if (!canAttempt(regional)) return Result(regional.lines, maxOf(1, regional.unresolved),
            fallbackBlocked = true, mlReadings = regional.wordReadings, tessReadings = wholeTess)
        val started = SystemClock.elapsedRealtime()
        val rows = regional.rows.map { row -> row.whole.firstOrNull { it.isNotEmpty() }.orEmpty() }
        val cells = rows.flatten()
        val page = wholePage(original, regional, wholeTess)
        if (!page.tokensMatch) return Result(regional.lines, maxOf(1, regional.unresolved),
            fallbackBlocked = true, mlReadings = regional.wordReadings, tessReadings = wholeTess)
        val mlReadings = regional.wordReadings.toMutableList()
        val tessReadings = wholeTess.toMutableList()
        var contextMappingIncomplete = false
        val uncovered = page.uncovered
        val orphanWords = page.orphanWords
        val orphanObservations = page.orphanObservations
        val boundary = page.boundary
        val backgroundIndices = page.backgroundIndices
        val uncoveredIndependent = page.uncoveredIndependent
        // Cell and strip reads cannot change a page that is already closed, and on large
        // photos the strips took most of the scan (331 of 470 s for a 3788x4818 invoice).
        val settled = closedBeforeCellReads(original, regional, wholeTess)
        val paddleReadings = if (settled) emptyList() else paddle.recognize(original, cells)
        val paddleCompleted = SystemClock.elapsedRealtime()
        val cellRows = rows.flatMapIndexed { index, row -> List(row.size) { index } }
        val observations = List(cells.size) { mutableListOf<OpticalCellConsensus.Observation>() }
        fun addWords(family: OpticalCellConsensus.Family, readings: List<List<OcrLine>>, prefix: String) {
            readings.forEachIndexed { readingIndex, words ->
                val assigned = assign(words, cells, original.width, original.height)
                assigned.forEachIndexed { cellIndex, assignedWords ->
                    if (assignedWords.isNotEmpty()) {
                        val ordered = assignedWords.sortedBy { it.left }
                        observations[cellIndex] += OpticalCellConsensus.Observation(
                            text = ordered.joinToString(" ") { it.text },
                            confidence = ordered.minOf { it.confidence ?: 0f },
                            sourceId = "$prefix-$readingIndex", family = family,
                            wordConfidences = ordered.map { OpticalCellConsensus.WordConfidence(it.text, it.confidence ?: 0f) })
                    }
                }
            }
        }
        fun rebuild() {
            observations.forEach { it.clear() }
            addWords(OpticalCellConsensus.Family.ML, mlReadings, "ml")
            addWords(OpticalCellConsensus.Family.TESSERACT, tessReadings, "tess")
            paddleReadings.forEach { group ->
                group.observations.forEach { observation ->
                    observations[group.index] += OpticalCellConsensus.Observation(
                        text = observation.text, confidence = observation.confidence,
                        sourceId = observation.rasterId, family = OpticalCellConsensus.Family.PADDLE,
                        minimumCharacterConfidence = observation.characters.minOfOrNull { it.confidence })
                }
            }
        }
        rebuild()
        val firstDecisions = observations.map { OpticalCellConsensus.choose(it) }
        val uncertainRows = firstDecisions.indices.filter { firstDecisions[it].text == null }.map { cellRows[it] }.toSet()
        // Neighboring strips can expose a character that lies just outside a detector box.
        // Plan the complete bounded set before reading; later disagreements are retained.
        val centers = if (settled) emptyList() else uncertainRows.flatMap { index -> listOf(index - 1, index, index + 1) }
            .filter { it in rows.indices }.distinct().sorted().take(MAX_CONTEXT_CENTERS)
        for (center in centers) {
            checkpoint()
            val included = rows.subList((center - 1).coerceAtLeast(0), (center + 2).coerceAtMost(rows.size)).flatten()
            for (contrast in listOf(false, true)) {
                checkpoint()
                val crop = RegionRaster.prepare(original, included, if (contrast) 3.0 else 2.0, true, contrast)
                var mlInterrupted = false
                try {
                    val reading = try { ml.recognizeRegion(crop.bitmap) }
                    catch (error: InterruptedException) { mlInterrupted = true; throw error }
                    val mappedMl = reading.words.map { RegionRaster.map(it, crop, original.width, original.height) }
                    if (mappedMl.any { it == null } || reading.suggestedRotation != 0 ||
                        !ReadingInventory.matches(reading.text, reading.lines.map { it.text }, reading.words.map { it.text })) {
                        contextMappingIncomplete = true
                    }
                    mlReadings += mappedMl.filterNotNull()
                    tessReadings += tess.recognizeRegion(crop.bitmap).map { words ->
                        val mapped = words.map { RegionRaster.map(it, crop, original.width, original.height) }
                        if (mapped.any { it == null }) contextMappingIncomplete = true
                        mapped.filterNotNull()
                    }
                } finally { if (!mlInterrupted && !crop.bitmap.isRecycled) crop.bitmap.recycle() }
            }
        }
        rebuild()
        val contextsCompleted = SystemClock.elapsedRealtime()
        val primaryCells = regional.wordReadings.take(3).map { assign(it, cells, original.width, original.height) }
        val decisions = observations.mapIndexed { index, evidence ->
            val decision = OpticalCellConsensus.choose(evidence)
            val selected = decision.text
            // Weak detector observations still protect against deleting a quantity.
            // A missing token is unresolved, even when its confidence was low.
            val dropsPrimary = selected != null && primaryCells.any { primary ->
                val originalText = primary[index].sortedBy { it.left }.joinToString(" ") { it.text }
                RegionConsensus.tokens(selected).size < RegionConsensus.tokens(originalText).size &&
                    RegionConsensus.includes(originalText, selected)
            }
            if (dropsPrimary) decision.copy(text = null, blockers = decision.blockers + "omitted_primary_tokens") else decision
        }
        val unresolved = decisions.count { it.text == null }
        val output = cells.mapIndexed { index, cell -> cell.copy(text = decisions[index].text ?: cell.text) }
        // A context word without exactly one owner cell cannot reach the output,
        // so accepting would drop it silently. Keep the page pending instead: a
        // false block costs a recapture, a false acceptance loses text. The same
        // flags forbid the later recovery route. Measured on the 22 device photos
        // (2026-10-18), every unowned Tesseract context word without a letter or
        // digit was paper-edge or blank-paper noise (91 of 99 were «|»), and that
        // noise blocked every page that reached this check, while unowned words
        // with letters or digits included real amounts and quantities. So only
        // symbol-only Tesseract words are dropped, never ML words, never by length.
        val unownedContextMl = unownedWords(mlReadings.drop(3), cells, original.width, original.height, null)
        val unownedContextTess = unownedTesseractContext(tessReadings.drop(wholeTess.size), cells, original.width, original.height)
        val uncoveredContextMl = unownedContextMl.isNotEmpty()
        val uncoveredContextTess = unownedContextTess.isNotEmpty()
        val coverage = Coverage(uncovered, uncoveredIndependent, uncoveredContextMl, uncoveredContextTess,
            contextMappingIncomplete || regional.contextMappingIncomplete)
        val fallbackBlocked = coverage.blocked
        val report = JSONObject().put("reference_used", false).put("source_modified", false)
            .put("paddle_elapsed_ms", paddleCompleted - started)
            .put("contexts_elapsed_ms", contextsCompleted - paddleCompleted)
            .put("settled_before_cell_reads", settled)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("unresolved_cells", unresolved).put("uncovered_primary_text", uncovered)
            .put("uncovered_independent_text", uncoveredIndependent)
            .put("context_mapping_incomplete", contextMappingIncomplete)
            .put("regional_context_mapping_incomplete", regional.contextMappingIncomplete)
            .put("uncovered_context_ml", uncoveredContextMl)
            .put("uncovered_context_tess", uncoveredContextTess)
            .put("unowned_context_words", JSONArray((unownedContextMl.map { "ML" to it } + unownedContextTess.map { "TESSERACT" to it })
                .map { (family, word) ->
                    JSONObject().put("family", family).put("text", word.text).put("confidence", word.confidence)
                        .put("left", word.left).put("top", word.top).put("right", word.right).put("bottom", word.bottom)
                }))
            .put("fallback_blocked", fallbackBlocked)
            .put("unresolved_for_acceptance", coverage.unresolved(unresolved))
            .put("document_boundary", boundary?.let { shape ->
                JSONObject().put("points", JSONArray(shape.points.map { JSONArray(listOf(it.x, it.y)) }))
                    .put("uncertainty_pixels", shape.uncertaintyPixels).put("raster_width", shape.rasterWidth)
                    .put("raster_height", shape.rasterHeight).put("minimum_border_support", shape.minimumBorderSupport)
            } ?: JSONObject.NULL)
            .put("orphan_words", JSONArray(orphanWords.mapIndexed { index, word ->
                JSONObject().put("text", word.text).put("confidence", word.confidence)
                    .put("confirmed_background", index in backgroundIndices)
                    .put("left", word.left).put("top", word.top).put("right", word.right).put("bottom", word.bottom)
                    .put("paddle", JSONArray(orphanObservations.firstOrNull { it.index == index }?.observations?.map {
                        JSONObject().put("text", it.text).put("confidence", it.confidence)
                            .put("minimum_character_confidence", it.characters.minOfOrNull { c -> c.confidence })
                    }.orEmpty()))
            }))
            .put("context_centers", JSONArray(centers)).put("cells", JSONArray(cells.indices.map { index ->
                val cell = cells[index]
                val decision = decisions[index]
                JSONObject().put("index", index).put("primary", cell.text)
                    .put("left", cell.left).put("top", cell.top).put("right", cell.right).put("bottom", cell.bottom)
                    .put("selected", decision.text ?: JSONObject.NULL)
                    .put("families", JSONArray(decision.supportingFamilies.map { it.name }))
                    .put("blockers", JSONArray(decision.blockers))
                    .put("observations", JSONArray(observations[index].map { observation ->
                        JSONObject().put("family", observation.family.name).put("text", observation.text)
                            .put("confidence", observation.confidence.toDouble())
                            .put("minimum_character_confidence", observation.minimumCharacterConfidence?.toDouble() ?: JSONObject.NULL)
                            .put("source_id", observation.sourceId)
                    }))
            }))
        checkpoint()
        trace.writeText(report.toString(2))
        return Result(output, coverage.unresolved(unresolved),
            evidence = cells.indices.map { index ->
                FallbackEvidenceGuard.CellEvidence(cells[index], observations[index].toList(), decisions[index])
            }, fallbackBlocked = fallbackBlocked,
            mlReadings = mlReadings.map { it.toList() }, tessReadings = tessReadings.map { it.toList() },
            contextMappingIncomplete = contextMappingIncomplete)
    }

    private fun assign(words: List<OcrLine>, cells: List<OcrLine>, width: Int, height: Int) =
        OpticalCellAssignment.assign(words, cells, width, height)

    private fun checkpoint() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Comprobación óptica interrumpida.")
    }

    /** Consulted text that has no single place in this verifier's output cells. */
    internal data class Coverage(
        val uncoveredPrimary: Boolean,
        val uncoveredIndependent: Boolean,
        val uncoveredContextMl: Boolean,
        val uncoveredContextTess: Boolean,
        val contextMappingIncomplete: Boolean,
    ) {
        val blocked: Boolean get() = uncoveredPrimary || uncoveredIndependent || uncoveredContextMl ||
            uncoveredContextTess || contextMappingIncomplete

        /** Acceptance needs every cell decided and every consulted word placed. */
        fun unresolved(undecidedCells: Int): Int = undecidedCells + if (blocked) 1 else 0
    }

    companion object {
        private const val MAX_CONTEXT_CENTERS = 12
        private const val MAX_CELLS = 64
        /** A sign, currency or percentage can belong to an amount even when read apart from it. */
        private const val MEANINGFUL_SIGNS = "$€-−–—%/"

        /** Words without exactly one owner cell; they cannot appear in the output. */
        internal fun unownedWords(readings: List<List<OcrLine>>, cells: List<OcrLine>, width: Int, height: Int,
            minimumConfidence: Float?): List<OcrLine> = readings.flatMap { words ->
            words.filter { word ->
                word.text.isNotBlank() && (minimumConfidence == null || (word.confidence ?: 0f) >= minimumConfidence) &&
                    OpticalCellAssignment.assign(listOf(word), cells, width, height).count { it.isNotEmpty() } != 1
            }
        }

        /** Unowned Tesseract strip words, without symbol-only edge noise such as «|». */
        internal fun unownedTesseractContext(readings: List<List<OcrLine>>, cells: List<OcrLine>, width: Int, height: Int) =
            unownedWords(readings, cells, width, height, .85f).filter { word ->
                word.text.any { it.isLetterOrDigit() || it in MEANINGFUL_SIGNS }
            }

        fun canAttempt(regional: RegionalOcrVerifier.Result): Boolean {
            if (regional.unassignedWholeText || regional.rows.isEmpty()) return false
            var count = 0
            for (row in regional.rows) {
                val canonical = row.whole.firstOrNull { it.isNotEmpty() } ?: return false
                count += canonical.size
                // Bound the complete offline recovery workload before loading
                // additional readers. Never truncate a document to fit this budget.
                if (count > MAX_CELLS) return false
            }
            return true
        }
    }
}

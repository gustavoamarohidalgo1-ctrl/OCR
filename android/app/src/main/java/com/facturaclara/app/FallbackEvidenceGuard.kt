package com.facturaclara.app

import java.text.Normalizer

/** Keeps already consulted optical evidence visible when a different recovery route succeeds. */
internal object FallbackEvidenceGuard {
    data class CellEvidence(
        val line: OcrLine,
        val observations: List<OpticalCellConsensus.Observation>,
        val decision: OpticalCellConsensus.Decision,
    )
    data class Conflict(val cellIndices: List<Int>, val reason: String)

    fun inspect(evidence: List<CellEvidence>, fallback: List<OcrLine>): List<Conflict> {
        data class Edge(val cell: Int, val legacy: Int, val distance: Float)
        val candidates = evidence.flatMapIndexed { index, cell -> fallback.mapIndexedNotNull { legacy, line ->
            if (!overlapsSameRow(cell.line, line)) null else Edge(index, legacy,
                OcrLayout.rowDistance(cell.line, listOf(line)) /
                    minOf(cell.line.bottom - cell.line.top, line.bottom - line.top))
        } }
        val bestCell = candidates.groupBy { it.cell }.mapValues { (_, edges) -> edges.minOf { it.distance } }
        val bestLegacy = candidates.groupBy { it.legacy }.mapValues { (_, edges) -> edges.minOf { it.distance } }
        // Tall detector envelopes can touch a neighboring row while their
        // printed baseline has a much closer owner. Require proximity to the
        // best baseline from both directions. Ties remain connected so genuine
        // ambiguity still blocks instead of choosing one occurrence arbitrarily.
        val edges = candidates.filter { edge ->
            edge.distance <= bestCell.getValue(edge.cell) + .15f &&
                edge.distance <= bestLegacy.getValue(edge.legacy) + .15f
        }
        val neighbors = evidence.indices.map { index -> edges.filter { it.cell == index }.map { it.legacy } }
        val reverse = fallback.indices.map { index -> evidence.indices.filter { index in neighbors[it] } }
        val visited = BooleanArray(evidence.size)
        val conflicts = mutableListOf<Conflict>()
        for (start in evidence.indices) {
            if (visited[start]) continue
            val cellIndices = mutableSetOf<Int>()
            val fallbackIndices = mutableSetOf<Int>()
            val pending = ArrayDeque<Int>()
            pending.add(start)
            visited[start] = true
            while (pending.isNotEmpty()) {
                val index = pending.removeFirst()
                cellIndices += index
                for (legacy in neighbors[index]) {
                    fallbackIndices += legacy
                    for (other in reverse[legacy]) if (!visited[other]) {
                        visited[other] = true
                        pending.add(other)
                    }
                }
            }
            val indices = cellIndices.sorted()
            val cells = indices.map { evidence[it] }
            val material = cells.any { it.decision.text != null || it.observations.any { observation ->
                qualified(observation) || completeMl(observation)
            } }
            if (fallbackIndices.isEmpty()) {
                if (material) conflicts += Conflict(indices, "unmapped_consulted_evidence")
                continue
            }
            val legacy = fallbackIndices.map { fallback[it] }
            // A connected region may join columns through a whole-line box, but
            // it may not borrow the same quantity from a neighboring physical row.
            if (OcrLayout.rows(cells.map { it.line }).size != 1 || OcrLayout.rows(legacy).size != 1) {
                if (material) conflicts += Conflict(indices, "ambiguous_fallback_geometry")
                continue
            }
            val legacyText = OcrLayout.text(legacy)
            val legacyTokens = RegionConsensus.tokens(legacyText)
            val legacyNumbers = literalNumbers(legacyText)
            val confirmed = cells.mapNotNull { cell -> cell.decision.text?.let { cell.line.copy(text = it) } }
            if (confirmed.isNotEmpty()) {
                val confirmedText = OcrLayout.text(confirmed)
                if (!subsequence(RegionConsensus.tokens(confirmedText), legacyTokens) ||
                    !subsequence(literalNumbers(confirmedText), legacyNumbers)) {
                    conflicts += Conflict(indices, "contradicted_confirmed_evidence")
                }
            }

            val required = cells.map { cell ->
                val observations = cell.observations.filter(::validObservation)
                val possibleShorter = observations.map { it.text } + if (cells.size == 1) listOf(legacyText) else emptyList()
                buildList {
                    cell.decision.text?.let { add(it) }
                    for (observation in observations.filter { qualified(it) || completeMl(it) }) {
                        val tokens = RegionConsensus.tokens(observation.text)
                        // Same omission rule as OpticalCellConsensus, extended to
                        // complete weak ML observations as IndependentPageConsensus.
                        val omitted = possibleShorter.any { shorter ->
                            val shortTokens = RegionConsensus.tokens(shorter)
                            shortTokens.isNotEmpty() && shortTokens.size < tokens.size &&
                                subsequence(shortTokens, tokens) && subsequence(shortTokens, legacyTokens)
                        }
                        val numericConflict = possibleShorter.any { alternative ->
                            RegionConsensus.tokens(alternative) == tokens &&
                                literalNumbers(alternative) != literalNumbers(observation.text) &&
                                subsequence(tokens, legacyTokens)
                        }
                        if (omitted || numericConflict) {
                            add(observation.text)
                            if (!subsequence(tokens, legacyTokens) || !subsequence(literalNumbers(observation.text), legacyNumbers)) {
                                conflicts += Conflict(indices, if (numericConflict) "conflicting_numeric_grouping" else "omitted_consulted_tokens")
                            }
                        }
                    }
                }.distinct()
            }
            // Independent cells are physical occurrences. Per-cell maximum
            // inventories preserve repeated items without counting alternative
            // optical observations of the same cell as extra printed copies.
            if (!coversInventory(legacyTokens, required, RegionConsensus::tokens) ||
                !coversInventory(legacyNumbers, required, ::literalNumbers)) {
                conflicts += Conflict(indices, "omitted_consulted_occurrence")
            }
        }
        return conflicts.distinct()
    }

    private fun overlapsSameRow(first: OcrLine, second: OcrLine): Boolean {
        val overlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
        if (overlap <= minOf(first.right - first.left, second.right - second.left) * .20f) return false
        return OcrLayout.rowDistance(first, listOf(second)) <=
            maxOf(first.bottom - first.top, second.bottom - second.top) * .65f
    }

    private fun coversInventory(available: List<String>, required: List<List<String>>, tokenize: (String) -> List<String>): Boolean {
        val counts = available.groupingBy { it }.eachCount()
        val needed = mutableMapOf<String, Int>()
        for (alternatives in required) {
            val local = mutableMapOf<String, Int>()
            for (text in alternatives) for ((token, count) in tokenize(text).groupingBy { it }.eachCount()) {
                local[token] = maxOf(local[token] ?: 0, count)
            }
            for ((token, count) in local) needed[token] = (needed[token] ?: 0) + count
        }
        return needed.all { (token, count) -> (counts[token] ?: 0) >= count }
    }

    private fun subsequence(shorter: List<String>, longer: List<String>): Boolean {
        var position = 0
        for (token in longer) if (position < shorter.size && token == shorter[position]) position++
        return position == shorter.size
    }

    private val numbers = Regex("[+\\-−]?(?:\\p{Nd}+(?:[.,:/\\-]\\p{Nd}+)*|[.,]\\p{Nd}+)")
    private fun literalNumbers(text: String): List<String> =
        numbers.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList()

    private fun validObservation(observation: OpticalCellConsensus.Observation) = observation.sourceId.isNotBlank() &&
        observation.confidence.isFinite() && observation.confidence in 0f..1f && RegionConsensus.tokens(observation.text).isNotEmpty()

    private fun completeMl(observation: OpticalCellConsensus.Observation): Boolean {
        if (observation.family != OpticalCellConsensus.Family.ML || !validObservation(observation)) return false
        if (observation.wordConfidences.isEmpty()) return true
        if (observation.wordConfidences.any { !it.confidence.isFinite() || it.confidence !in 0f..1f || RegionConsensus.tokens(it.text).isEmpty() }) return false
        val text = observation.wordConfidences.joinToString(" ") { it.text }
        return RegionConsensus.tokens(text) == RegionConsensus.tokens(observation.text) &&
            literalNumbers(text) == literalNumbers(observation.text)
    }

    /** Exactly the qualification thresholds of OpticalCellConsensus; no family quorum is invented here. */
    private fun qualified(observation: OpticalCellConsensus.Observation): Boolean {
        if (!validObservation(observation)) return false
        return when (observation.family) {
            OpticalCellConsensus.Family.ML -> completeMl(observation) &&
                if (observation.wordConfidences.isEmpty()) observation.confidence >= mlThreshold(observation.text)
                else observation.wordConfidences.all { it.confidence >= mlThreshold(it.text) }
            OpticalCellConsensus.Family.TESSERACT -> observation.confidence >= .85f
            OpticalCellConsensus.Family.PADDLE -> observation.confidence >= .95f &&
                observation.minimumCharacterConfidence?.let { it.isFinite() && it in .75f..1f } == true
        }
    }

    private fun mlThreshold(text: String): Float = if (text.any { it.isLetterOrDigit() }) .65f else .50f
}

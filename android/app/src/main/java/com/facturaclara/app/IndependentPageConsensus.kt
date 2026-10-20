package com.facturaclara.app

import java.text.Normalizer

/**
 * Reconciles complete-page observations on independently detected cells.
 *
 * Detection geometry, not a primary reader's text or boxes, defines the output.
 * Each source word occurrence can belong to one cell only. This class performs
 * no recognition, text correction, background removal or inferred word split.
 */
internal object IndependentPageConsensus {
    data class WordOccurrence(val readingIndex: Int, val wordIndex: Int, val word: OcrLine)

    data class Result(
        val decisions: List<OpticalCellConsensus.Decision>,
        val lines: List<OcrLine>,
        /** Complete reading -> detector cell -> observed words, with occurrences retained. */
        val assignedMl: List<List<List<OcrLine>>>,
        val assignedTess: List<List<List<OcrLine>>>,
        val uncoveredMl: List<WordOccurrence>,
        /** Only reliable whole-page Tesseract words require additional coverage proof. */
        val uncoveredTess: List<WordOccurrence>,
        val unresolved: Int,
    )

    fun evaluate(
        cells: List<OcrLine>,
        paddle: List<List<OpticalCellConsensus.Observation>>,
        wholeMl: List<List<OcrLine>>,
        wholeTess: List<List<OcrLine>>,
        width: Int,
        height: Int,
        extra: List<List<OpticalCellConsensus.Observation>> = List(cells.size) { emptyList() },
    ): Result {
        require(width > 0 && height > 0)
        require(paddle.size == cells.size && extra.size == cells.size)
        require(paddle.flatten().all { it.family == OpticalCellConsensus.Family.PADDLE })

        val ml = assign(wholeMl, cells, width, height)
        val tess = assign(wholeTess, cells, width, height)
        val observations = cells.indices.map { index -> (paddle[index] + extra[index]).toMutableList() }
        fun add(readings: List<List<List<OcrLine>>>, family: OpticalCellConsensus.Family, prefix: String) {
            readings.forEachIndexed { readingIndex, assigned ->
                assigned.forEachIndexed { cellIndex, words ->
                    if (words.isNotEmpty()) {
                        val ordered = words.sortedBy { it.left }
                        observations[cellIndex] += OpticalCellConsensus.Observation(
                            text = ordered.joinToString(" ") { it.text },
                            confidence = ordered.minOf { it.confidence ?: 0f },
                            sourceId = "$prefix-$readingIndex",
                            family = family,
                            wordConfidences = ordered.map {
                                OpticalCellConsensus.WordConfidence(it.text, it.confidence ?: 0f)
                            },
                        )
                    }
                }
            }
        }
        add(ml.assigned, OpticalCellConsensus.Family.ML, "whole-ml")
        add(tess.assigned, OpticalCellConsensus.Family.TESSERACT, "whole-tess")

        val decisions = observations.mapIndexed { index, evidence ->
            val decision = OpticalCellConsensus.choose(evidence)
            val selected = decision.text
            // Even weak primary evidence protects quantities and punctuation
            // from deletion. Repeated rows have separate occurrence buckets.
            val dropsPrimary = selected != null && ml.assigned.any { reading ->
                val original = reading[index].sortedBy { it.left }.joinToString(" ") { it.text }
                RegionConsensus.tokens(selected).size < RegionConsensus.tokens(original).size &&
                    RegionConsensus.includes(original, selected)
            }
            // A valid rectified crop can reveal a quantity that every original
            // whole-page detector missed. Its low confidence is not permission
            // to omit that observed token from a shorter two-family agreement.
            val contextualMl = extra[index].filter(::completeMlObservation)
            val dropsContext = selected != null && contextualMl.any { observation ->
                RegionConsensus.tokens(selected).size < RegionConsensus.tokens(observation.text).size &&
                    RegionConsensus.includes(observation.text, selected)
            }
            // `2 / 60.00` and `2/60.00` share lexical tokens but represent
            // different literal numeric groups. Preserve this ambiguity even
            // when the complete ML observation has weak character confidence.
            val numericConflict = selected != null && evidence.filter(::completeMlObservation).any { observation ->
                RegionConsensus.tokens(observation.text) == RegionConsensus.tokens(selected) &&
                    literalNumbers(observation.text) != literalNumbers(selected)
            }
            val additionalBlockers = buildList {
                if (dropsPrimary) add("omitted_primary_tokens")
                if (dropsContext) add("omitted_context_tokens")
                if (numericConflict) add("conflicting_numeric_grouping")
            }
            if (additionalBlockers.isNotEmpty()) decision.copy(text = null, blockers = decision.blockers + additionalBlockers)
            else decision
        }
        val qualifiedTess = tess.uncovered.filter { (it.word.confidence ?: 0f) >= .85f }
        // A caller may establish that a Tesseract orphan is outside a positively
        // detected document, but this pure reconciliation never assumes that.
        val coverageFailure = ml.uncovered.isNotEmpty() || qualifiedTess.isNotEmpty()
        return Result(
            decisions = decisions,
            lines = cells.mapIndexed { index, cell -> cell.copy(text = decisions[index].text ?: cell.text) },
            assignedMl = ml.assigned,
            assignedTess = tess.assigned,
            uncoveredMl = ml.uncovered,
            uncoveredTess = qualifiedTess,
            unresolved = decisions.count { it.text == null } + if (coverageFailure || cells.isEmpty()) 1 else 0,
        )
    }

    private data class Assignment(
        val assigned: List<List<List<OcrLine>>>,
        val uncovered: List<WordOccurrence>,
    )

    private val numbers = Regex("[+\\-−]?(?:\\p{Nd}+(?:[.,:/\\-]\\p{Nd}+)*|[.,]\\p{Nd}+)")

    private fun literalNumbers(text: String): List<String> =
        numbers.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList()

    /** Completeness/provenance check only; confidence deliberately has no acceptance threshold. */
    private fun completeMlObservation(observation: OpticalCellConsensus.Observation): Boolean {
        if (observation.family != OpticalCellConsensus.Family.ML || observation.sourceId.isBlank() ||
            !observation.confidence.isFinite() || observation.confidence !in 0f..1f ||
            RegionConsensus.tokens(observation.text).isEmpty()) return false
        if (observation.wordConfidences.isEmpty()) return true
        val words = observation.wordConfidences
        if (words.any { !it.confidence.isFinite() || it.confidence !in 0f..1f || RegionConsensus.tokens(it.text).isEmpty() }) return false
        val inventory = words.joinToString(" ") { it.text }
        return RegionConsensus.tokens(inventory) == RegionConsensus.tokens(observation.text) &&
            literalNumbers(inventory) == literalNumbers(observation.text)
    }

    private fun assign(readings: List<List<OcrLine>>, cells: List<OcrLine>, width: Int, height: Int): Assignment {
        val uncovered = mutableListOf<WordOccurrence>()
        val assigned = readings.mapIndexed { readingIndex, words ->
            val buckets = List(cells.size) { mutableListOf<OcrLine>() }
            words.forEachIndexed { wordIndex, word ->
                if (word.text.isNotBlank()) {
                    // Assign each occurrence separately; value-based sets would
                    // silently collapse repeated observations with equal boxes.
                    val assignment = OpticalCellAssignment.assign(listOf(word), cells, width, height)
                    val owners = assignment.indices.filter { assignment[it].isNotEmpty() }
                    if (owners.size == 1) buckets[owners.single()] += word
                    else uncovered += WordOccurrence(readingIndex, wordIndex, word)
                }
            }
            buckets.map { it.toList() }
        }
        return Assignment(assigned, uncovered)
    }
}

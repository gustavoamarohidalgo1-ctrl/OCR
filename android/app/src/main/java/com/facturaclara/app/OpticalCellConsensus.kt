package com.facturaclara.app

import java.text.Normalizer

/** Selects a complete observed cell. Confidence gates are heuristics, not accuracy guarantees. */
object OpticalCellConsensus {
    enum class Family { ML, PADDLE, TESSERACT }

    data class WordConfidence(val text: String, val confidence: Float)

    data class Observation(
        val text: String,
        val confidence: Float,
        // Stable identity of the raster, independent of model or repeated inference.
        val sourceId: String,
        val family: Family,
        val minimumCharacterConfidence: Float? = null,
        val wordConfidences: List<WordConfidence> = emptyList(),
    )

    data class Decision(
        val text: String?,
        val supportingFamilies: Set<Family>,
        val supportingObservations: List<Observation>,
        val disagreements: List<Observation>,
        val blockers: List<String>,
    )

    private data class Content(
        val tokens: List<String>,
        val numbers: List<String>,
    )

    private data class Candidate(
        val content: Content,
        val families: Set<Family>,
        val observations: List<Observation>,
    )

    fun choose(observations: List<Observation>): Decision {
        val qualified = observations.filter(::qualified)
        val candidates = qualified.groupBy { content(it.text) }.mapNotNull { (content, readings) ->
            val families = readings.groupBy { it.family }.filter { (family, familyReadings) ->
                family != Family.PADDLE || familyReadings.map { it.sourceId }.distinct().size >= 2
            }.keys
            if (families.size < 2) null else Candidate(content, families,
                readings.filter { it.family in families })
        }
        if (candidates.size != 1) {
            return Decision(null, emptySet(), emptyList(), observations,
                listOf(if (candidates.isEmpty()) "insufficient_independent_support" else "competing_candidates"))
        }
        val candidate = candidates.single()
        val disagreements = observations.filter { content(it.text) != candidate.content }
        // Spaces around punctuation can distinguish a quantity and a price from a
        // single code, date or fraction. A token-only agreement cannot resolve it.
        val numericConflict = qualified.any { observation ->
            val observed = content(observation.text)
            observed.tokens == candidate.content.tokens && observed.numbers != candidate.content.numbers
        }
        if (numericConflict) {
            return Decision(null, candidate.families, candidate.observations, disagreements,
                listOf("conflicting_numeric_grouping"))
        }
        // Even a single good observation with additional tokens is unresolved evidence.
        // A shorter agreement must not delete quantities, punctuation, or repeated entries.
        val omittedEvidence = qualified.any { observation ->
            val observed = tokens(observation.text)
            candidate.content.tokens.size < observed.size && isSubsequence(candidate.content.tokens, observed)
        }
        if (omittedEvidence) {
            return Decision(null, candidate.families, candidate.observations, disagreements,
                listOf("additional_observed_tokens"))
        }
        // The spelling, punctuation, case and whitespace come from this observation verbatim.
        // Sorting only makes the representative independent of caller iteration order.
        val representative = candidate.observations.minWithOrNull(
            compareBy<Observation> { it.family.ordinal }.thenBy { it.sourceId }.thenBy { it.text },
        )!!
        return Decision(representative.text, candidate.families, candidate.observations, disagreements, emptyList())
    }

    private fun qualified(observation: Observation): Boolean {
        if (observation.sourceId.isBlank() || tokens(observation.text).isEmpty() ||
            !validConfidence(observation.confidence)) return false
        return when (observation.family) {
            Family.ML -> if (observation.wordConfidences.isEmpty()) {
                observation.confidence >= mlThreshold(observation.text)
            } else {
                content(observation.wordConfidences.joinToString(" ") { it.text }) == content(observation.text) &&
                    observation.wordConfidences.all { word ->
                        tokens(word.text).isNotEmpty() && validConfidence(word.confidence) &&
                            word.confidence >= mlThreshold(word.text)
                    }
            }
            Family.TESSERACT -> observation.confidence >= .85f
            Family.PADDLE -> observation.confidence >= .95f &&
                observation.minimumCharacterConfidence?.let { validConfidence(it) && it >= .75f } == true
        }
    }

    private fun validConfidence(value: Float): Boolean = value.isFinite() && value in 0f..1f

    private fun mlThreshold(text: String): Float = if (text.any { it.isLetterOrDigit() }) .65f else .50f

    // Same literal numeric groups as NUMBERS in tools/measure_receipt_batch.py.
    // Nd matches Python's Unicode-aware \d; no decimal, sign or date normalization.
    private val numbers = Regex("[+\\-−]?(?:\\p{Nd}+(?:[.,:/\\-]\\p{Nd}+)*|[.,]\\p{Nd}+)")

    private fun content(text: String): Content = Content(tokens(text),
        numbers.findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList())

    private fun tokens(text: String): List<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{Z}]")
            .findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList()

    private fun isSubsequence(shorter: List<String>, longer: List<String>): Boolean {
        var index = 0
        for (token in longer) {
            if (index < shorter.size && shorter[index] == token) index++
        }
        return index == shorter.size
    }
}

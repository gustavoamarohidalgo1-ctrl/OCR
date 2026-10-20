package com.facturaclara.app

import java.text.Normalizer

/**
 * Per-fragment vote among the bundled reader families for pages that could not be
 * confirmed. A fragment's text changes only when one exact reading is produced by at
 * least two different families (ML Kit, Paddle, Tesseract) and the primary ML Kit
 * text is not among the best supported. Nothing is merged from several readings and
 * no character is invented: the winner is a complete string some reader produced.
 */
internal object EngineVote {
    enum class Family { ML, PADDLE, TESSERACT }
    data class Candidate(val family: Family, val text: String)

    fun choose(primary: String, candidates: List<Candidate>): String {
        val primaryKey = normalize(primary)
        val support = support(primary, candidates)
        val strongest = support.values.maxOfOrNull { it.size } ?: return primary
        if (strongest < 2 || support[primaryKey]?.size == strongest) return primary
        // Two different readings with equal support: the evidence does not decide.
        val winners = support.filterValues { it.size == strongest }.keys
        return if (winners.size == 1) winners.single() else primary
    }

    /**
     * Whether some set of Paddle readings could make [choose] differ from its result for
     * the other families' [candidates]. Paddle adds one family to each text it produces, so
     * only a text that another family already supports at least as broadly as the primary
     * could overtake it, or tie with the current winner. Otherwise reading Paddle is moot.
     */
    fun paddleCanChange(primary: String, candidates: List<Candidate>): Boolean {
        val primaryKey = normalize(primary)
        val support = support(primary, candidates.filter { it.family != Family.PADDLE })
        val primarySupport = support[primaryKey]?.size ?: 0
        return support.any { (key, families) -> key != primaryKey && families.size >= primarySupport }
    }

    private fun support(primary: String, candidates: List<Candidate>): Map<String, Set<Family>> {
        val support = linkedMapOf<String, MutableSet<Family>>()
        (listOf(Candidate(Family.ML, primary)) + candidates).forEach { candidate ->
            val key = normalize(candidate.text)
            if (key.isNotEmpty()) support.getOrPut(key) { mutableSetOf() } += candidate.family
        }
        return support
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFC).split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }.joinToString(" ")
}

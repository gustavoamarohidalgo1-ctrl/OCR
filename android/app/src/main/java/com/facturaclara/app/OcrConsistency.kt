package com.facturaclara.app

import java.text.Normalizer

data class OcrComparison(val contentMatches: Boolean, val numbersMatch: Boolean)

/** Operational checks, not a calibrated estimate of transcription accuracy. */
object OcrConsistency {
    enum class ConfidenceLevel { CLEAR, CAUTION, SEVERE }

    fun compare(first: String, second: String): OcrComparison = OcrComparison(
        contentMatches = content(first) == content(second),
        numbersMatch = numericTokens(first) == numericTokens(second),
    )

    fun confidenceLevel(lineScores: List<Float>, numericElementScores: List<Float>): ConfidenceLevel {
        val scores = lineScores + numericElementScores
        if (scores.any { !it.isFinite() || it < 0.50f }) return ConfidenceLevel.SEVERE
        if (lineScores.any { it < 0.72f } || numericElementScores.any { it < 0.85f }) {
            return ConfidenceLevel.CAUTION
        }
        return ConfidenceLevel.CLEAR
    }

    private fun content(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Layout may change tabs, line breaks, or spacing around punctuation.
        // It must not turn two words/codes into one: "DE LA" != "DELA",
        // "AB 12" != "AB12". Keeping token boundaries also protects quantities.
        return Regex("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{Z}]").findAll(normalized)
            .map { it.value }.toList()
    }

    private fun numericTokens(text: String): Map<String, Int> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        // Only remove spacing adjacent to a separator between digits. Do not
        // concatenate two quantities merely because OCR separated them by spaces.
        val joined = Regex("(?<=\\d)[\\s\\p{Z}]*([.,/\\-])[\\s\\p{Z}]*(?=\\d)")
            .replace(normalized) { it.groupValues[1] }
        return Regex("\\d+(?:[.,/\\-]\\d+)*").findAll(joined)
            .map { it.value }.groupingBy { it }.eachCount()
    }
}

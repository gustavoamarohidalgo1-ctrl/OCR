package com.facturaclara.app

import java.text.Normalizer

/**
 * Review marks for a page whose readings could not be fully confirmed. The primary
 * reading is kept verbatim: nothing is corrected, completed or removed. Fragments
 * without support from the second reading, with low engine confidence or with an
 * impossible amount are only flagged so the PDF can highlight them for the reader.
 */
object ReadingReview {
    /** Heuristic, not a probability of correctness. */
    const val MIN_CONFIDENCE = .70f

    /**
     * With word boxes from both readings, every primary word needs its own second-reading
     * word with the same text at the same place; each second word supports one primary
     * word only, so a single token cannot vouch for two different columns. Without word
     * boxes, the fragment's tokens are compared against the overlapping second fragments.
     */
    fun mark(
        primary: List<OcrLine>,
        second: List<OcrLine>,
        primaryWords: List<OcrLine> = emptyList(),
        secondWords: List<OcrLine> = emptyList(),
    ): List<OcrLine> {
        val unsupportedWords = if (primaryWords.isNotEmpty() && secondWords.isNotEmpty())
            unsupported(primaryWords, secondWords) else null
        return primary.map { line ->
            val weak = line.confidence?.let { it < MIN_CONFIDENCE } ?: false
            val words = unsupportedWords?.let { primaryWords.indices.filter { inside(primaryWords[it], line) } }.orEmpty()
            val unconfirmed = if (unsupportedWords != null && words.isNotEmpty()) words.any { it in unsupportedWords }
                else !supported(line, second)
            if (weak || unconfirmed || impossibleAmount(line.text)) line.copy(doubtful = true) else line
        }
    }

    /**
     * An amount whose separators fit no number format: thousands groups mixing «.» and
     * «,», a thousands separator equal to the decimal one, or a first group of more than
     * three digits (as in «51,431.963.91» or «1434.275.66»). Either a separator was misread,
     * even when both readers agree, or the document itself is ambiguous; the fragment needs
     * review and is never rewritten.
     */
    fun impossibleAmount(text: String): Boolean = groupedAmount.findAll(text).any { match ->
        val (first, groups, decimal) = match.destructured
        val separators = groups.filter { it == '.' || it == ',' }.toSet()
        first.length > 3 || separators.size > 1 || decimal.single() in separators
    }

    // Digits, at least one three-digit group and two decimals; not part of a longer number.
    private val groupedAmount = Regex("(?<!\\d)(?<!\\d[.,])(\\d+)((?:[.,]\\d{3})+)([.,])\\d{2}(?![.,]?\\d)")

    /** One-to-one assignment of equal words, nearest pairs first. */
    private fun unsupported(primary: List<OcrLine>, second: List<OcrLine>): Set<Int> {
        data class Pair(val first: Int, val second: Int, val distance: Float)
        val candidates = buildList {
            primary.forEachIndexed { i, word ->
                val text = key(word.text)
                second.forEachIndexed { j, other ->
                    if (key(other.text) == text && overlaps(word, other)) add(Pair(i, j, distance(word, other)))
                }
            }
        }.sortedWith(compareBy<Pair> { it.distance }.thenBy { it.first }.thenBy { it.second })
        val usedPrimary = mutableSetOf<Int>()
        val usedSecond = mutableSetOf<Int>()
        candidates.forEach { if (it.first !in usedPrimary && it.second !in usedSecond) { usedPrimary += it.first; usedSecond += it.second } }
        return primary.indices.filter { it !in usedPrimary }.toSet()
    }

    /**
     * Each token of the fragment must appear in the second reading's fragments that
     * share its position. Fragmentation may differ between readings, so tokens are
     * compared as a multiset over all overlapping fragments.
     */
    private fun supported(line: OcrLine, second: List<OcrLine>): Boolean {
        val wanted = tokens(line.text)
        if (wanted.isEmpty()) return true
        val available = second.filter { overlaps(line, it) }.flatMap { tokens(it.text) }
            .groupingBy { it }.eachCount().toMutableMap()
        return wanted.all { token ->
            val count = available[token] ?: 0
            if (count > 0) { available[token] = count - 1; true } else false
        }
    }

    private fun inside(word: OcrLine, line: OcrLine): Boolean {
        val x = (word.left + word.right) / 2f
        val y = (word.top + word.bottom) / 2f
        return x >= line.left && x <= line.right && y >= line.top && y <= line.bottom
    }

    private fun overlaps(first: OcrLine, second: OcrLine): Boolean {
        val vertical = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        val horizontal = minOf(first.right, second.right) - maxOf(first.left, second.left)
        return horizontal > 0f && vertical > minOf(first.bottom - first.top, second.bottom - second.top) * .5f
    }

    private fun distance(first: OcrLine, second: OcrLine): Float {
        val dx = (first.left + first.right - second.left - second.right) / 2f
        val dy = (first.top + first.bottom - second.top - second.bottom) / 2f
        return dx * dx + dy * dy
    }

    private fun key(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun tokens(text: String): List<String> =
        key(text).split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }
}

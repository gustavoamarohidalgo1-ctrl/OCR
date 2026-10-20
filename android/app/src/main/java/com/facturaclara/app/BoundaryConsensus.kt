package com.facturaclara.app

import java.text.Normalizer

/**
 * Narrow optical recovery of alphabetic word boundaries. Selects an unchanged ML
 * observation; never changes letters, case, punctuation, numbers or their order.
 * The caller must supply distinct optical region observations, not duplicate
 * registrations of the same prepared raster.
 */
internal object BoundaryConsensus {
    private const val INDEPENDENT_MINIMUM = .85f
    private val LETTER_WORD = Regex("[\\p{L}\\p{M}]+")
    private val NUMBERS = Regex("[+\\-−]?(?:\\d+(?:[.,:/\\-]\\d+)*|[.,]\\d+)")
    private data class WordNeed(val text: String, val start: Int, val end: Int, val fragment: OcrLine)
    private data class Candidate(val lines: List<OcrLine>, val changedWords: List<WordNeed>)

    /** Cheap gates only; does not load or invoke the independent reader. */
    fun canAttempt(whole: List<List<OcrLine>>, regions: List<List<OcrLine>>): Boolean =
        candidates(whole, regions).isNotEmpty()

    fun recovered(whole: List<List<OcrLine>>, regions: List<List<OcrLine>>,
        independentReadings: List<List<OcrLine>>): List<OcrLine>? {
        val supported = candidates(whole, regions).filter { candidate ->
            independentReadings.any { words -> corroborates(candidate, words, independentReadings) } &&
                !hasBoundaryDisagreement(candidate.lines, independentReadings)
        }
        val first = supported.firstOrNull() ?: return null
        val signature = RegionConsensus.tokens(OcrLayout.text(first.lines))
        return first.lines.takeIf { supported.all { RegionConsensus.tokens(OcrLayout.text(it.lines)) == signature } }
    }

    /**
     * Catch shared ML spacing mistakes even in rows that otherwise look stable.
     * Only literal alphabetic spans are compared: no joining amounts or codes.
     * This detects disagreement and blocks; it never supplies replacement text.
     */
    fun hasBoundaryDisagreement(candidate: List<OcrLine>, independentReadings: List<List<OcrLine>>): Boolean {
        for (fragment in candidate) {
            val text = nfc(fragment.text)
            val sourceWords = LETTER_WORD.findAll(text).toList()
            for (reading in independentReadings) {
                val words = reading.filter { reliableWord(it) && inside(it, fragment) && isLetterWord(it.text) }
                    .sortedBy { it.left }
                // One independently observed word covers two or more ML words.
                for (start in sourceWords.indices) {
                    var combined = sourceWords[start].value
                    for (end in start + 1 until sourceWords.size) {
                        val between = text.substring(sourceWords[end - 1].range.last + 1, sourceWords[end].range.first)
                        if (between.isEmpty() || !between.all(::white)) break
                        combined += sourceWords[end].value
                        if (words.any { nfc(it.text) == combined }) return true
                    }
                }
                // Multiple independent words cover one ML word, in spatial order.
                for (start in words.indices) {
                    var combined = nfc(words[start].text)
                    for (end in start + 1 until words.size) {
                        if (!nextWord(words[end - 1], words[end])) break
                        combined += nfc(words[end].text)
                        if (sourceWords.any { it.value == combined }) return true
                    }
                }
            }
        }
        return false
    }

    private fun candidates(whole: List<List<OcrLine>>, regions: List<List<OcrLine>>): List<Candidate> {
        if (whole.size < 2 || whole.any { it.isEmpty() || OcrLayout.text(it).isBlank() }) return emptyList()
        val wholeTexts = whole.map { nfc(OcrLayout.text(it)) }
        val reliableRegions = regions.filter(RegionConsensus::reliable)
        val candidates = mutableListOf<Candidate>()
        for (region in reliableRegions) {
            val rows = OcrLayout.rows(region)
            if (rows.size != 1) continue // A boundary rescue may not reorder printed rows.
            val ordered = rows.single()
            val text = nfc(OcrLayout.text(ordered))
            val characters = compact(text)
            val numeric = numbers(text)
            val tokens = RegionConsensus.tokens(text)
            if (reliableRegions.count { RegionConsensus.tokens(OcrLayout.text(it)) == tokens } < 2) continue
            if (wholeTexts.any { compact(it) != characters || numbers(it) != numeric }) continue
            // A reliable regional character disagreement is not a spacing issue.
            if (reliableRegions.any { compact(OcrLayout.text(it)) != characters || numbers(OcrLayout.text(it)) != numeric }) continue
            if (wholeTexts.all { RegionConsensus.tokens(it) == tokens }) continue
            val candidateBoundaries = boundaries(text)
            val changed = wholeTexts.flatMap { other ->
                val otherBoundaries = boundaries(other)
                (candidateBoundaries - otherBoundaries) + (otherBoundaries - candidateBoundaries)
            }.toSet()
            val alphabetic = changed.filter { position ->
                position > 0 && position < characters.length && letter(characters[position - 1]) && letter(characters[position])
            }.toSet()
            // Separating a letter from a digit changes a code, not just a word.
            if (changed.any { position -> position > 0 && position < characters.length &&
                    (letter(characters[position - 1]) || characters[position - 1].isDigit()) &&
                    (letter(characters[position]) || characters[position].isDigit()) && position !in alphabetic }) continue
            if (alphabetic.isEmpty()) continue
            var offset = 0
            val affected = mutableListOf<WordNeed>()
            for (fragment in ordered) {
                val fragmentText = nfc(fragment.text)
                for (match in LETTER_WORD.findAll(fragmentText)) {
                    val start = offset + compact(fragmentText.substring(0, match.range.first)).length
                    val end = start + match.value.length
                    if (alphabetic.any { it in start..end }) affected += WordNeed(match.value, start, end, fragment)
                }
                offset += compact(fragmentText).length
            }
            if (affected.isNotEmpty()) candidates += Candidate(region, affected)
        }
        return candidates.distinctBy { RegionConsensus.tokens(OcrLayout.text(it.lines)) }
    }

    private fun corroborates(candidate: Candidate, supporting: List<OcrLine>, allReadings: List<List<OcrLine>>): Boolean {
        val selected = mutableListOf<OcrLine>()
        val used = mutableSetOf<Int>()
        for (needed in candidate.changedWords) {
            val matches = supporting.indices.filter { index ->
                val word = supporting[index]
                reliableWord(word) && nfc(word.text) == needed.text && inside(word, needed.fragment)
            }
            // Repeated words cannot be arbitrarily assigned to multiple locations.
            if (matches.size != 1 || !used.add(matches.single())) return false
            selected += supporting[matches.single()]
        }
        if (selected.zipWithNext().any { (a, b) -> !nextWord(a, b) }) return false
        // Any confident contradictory observation at the supported positions
        // remains a contradiction. Unrelated unchanged symbols elsewhere in the
        // row, e.g. a poorly read #, do not take part in boundary corroboration.
        val expected = candidate.changedWords.map { it.text }
        for (reading in allReadings) {
            val overlapping = reading.filter { word -> reliableWord(word) && selected.any { overlaps(word, it) } }
                .sortedBy { it.left }
            if (overlapping.isNotEmpty() && overlapping.map { nfc(it.text) } != expected) return false
        }
        return true
    }

    private fun reliableWord(word: OcrLine): Boolean = word.text.isNotEmpty() && word.text.none(::white) &&
        word.confidence?.let { it.isFinite() && it >= INDEPENDENT_MINIMUM } == true

    private fun inside(word: OcrLine, fragment: OcrLine): Boolean {
        val intersection = maxOf(0f, minOf(word.right, fragment.right) - maxOf(word.left, fragment.left)) *
            maxOf(0f, minOf(word.bottom, fragment.bottom) - maxOf(word.top, fragment.top))
        val area = (word.right - word.left) * (word.bottom - word.top)
        return intersection >= area * .8f
    }

    private fun overlaps(a: OcrLine, b: OcrLine): Boolean {
        val intersection = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left)) *
            maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
        return intersection >= smaller * .5f
    }

    private fun nextWord(a: OcrLine, b: OcrLine): Boolean {
        if (b.left < a.right) return false
        val vertical = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return vertical >= minOf(a.bottom - a.top, b.bottom - b.top) * .5f
    }

    private fun boundaries(text: String): Set<Int> {
        var offset = 0
        var separated = false
        val result = mutableSetOf<Int>()
        for (char in nfc(text)) {
            if (white(char)) separated = true else {
                if (separated && offset > 0) result += offset
                offset++
                separated = false
            }
        }
        return result
    }

    private fun nfc(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
    private fun white(char: Char) = char.isWhitespace() || Character.isSpaceChar(char)
    private fun letter(char: Char) = char.isLetter() || Character.getType(char) in setOf(
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    private fun isLetterWord(text: String) = LETTER_WORD.matches(nfc(text))
    private fun compact(text: String) = nfc(text).filterNot(::white)
    private fun numbers(text: String) = NUMBERS.findAll(nfc(text)).map { it.value }.toList()
}

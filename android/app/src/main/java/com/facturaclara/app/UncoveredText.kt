package com.facturaclara.app

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Text the page reading missed entirely, such as a large bold total or white figures on a
 * dark bar. A text-detector box that no final line covers is read by Paddle; its text is
 * kept only when two different rasters of the box produce the same string, one of them
 * with high confidence, it holds at least three letters or digits, and no line beside it
 * already holds its words. Boxes of lines removed on purpose (decorative rows) count as
 * covered. Kept text is always marked doubtful: a single reader family saw it, so the
 * page goes to review and the PDF highlights it.
 */
internal object UncoveredText {
    /** Measured on the 22 test photos: below it, recovered boxes were mostly noise. */
    const val MIN_CONFIDENCE = .9f
    /** One or two isolated characters are usually a logo mark or a stroke, not text. */
    private const val MIN_CHARACTERS = 3
    private const val MAX_COVERED = .25f
    private const val MAX_LINE_HEIGHT = .12f
    private const val MAX_LINE_AREA = .05f

    data class Reading(val text: String, val confidence: Float, val rasterId: String)

    /** Indices of detector [boxes] that the final [lines] do not already cover. */
    fun uncovered(boxes: List<OcrLine>, lines: List<OcrLine>): List<Int> = boxes.indices.filter { index ->
        val box = boxes[index]
        val area = (box.right - box.left) * (box.bottom - box.top)
        val x = (box.left + box.right) / 2f
        val y = (box.top + box.bottom) / 2f
        area > 0f && lines.none { x in it.left..it.right && y in it.top..it.bottom } &&
            lines.sumOf { overlap(box, it).toDouble() } < MAX_COVERED * area
    }

    /** The single text that two distinct rasters produced, or null when nothing qualifies. */
    fun agreed(readings: List<Reading>): Reading? {
        val support = readings.groupBy { normalize(it.text) }
            .filterKeys { text -> text.count(Char::isLetterOrDigit) >= MIN_CHARACTERS }
            .filterValues { same -> same.map { it.rasterId }.distinct().size >= 2 && same.maxOf { it.confidence } >= MIN_CONFIDENCE }
        val strongest = support.values.maxOfOrNull { same -> same.map { it.rasterId }.distinct().size } ?: return null
        val winners = support.filterValues { same -> same.map { it.rasterId }.distinct().size == strongest }
        // Two different texts with equal support: the evidence does not decide.
        val (text, same) = winners.entries.singleOrNull() ?: return null
        val best = same.maxBy { it.confidence }
        return Reading(text, best.confidence, best.rasterId)
    }

    /**
     * Whether a final line beside [box] (same row, overlapping or touching it horizontally)
     * already holds every word of [text]. Whole words only: «5.35» is not inside «15.35»,
     * and the same amount in another column of the row is a different figure.
     */
    fun alreadyRead(text: String, box: OcrLine, lines: List<OcrLine>): Boolean {
        val height = box.bottom - box.top
        val near = lines.filter { line ->
            min(line.bottom, box.bottom) - max(line.top, box.top) > .5f * min(height, line.bottom - line.top) &&
                min(line.right, box.right + height) - max(line.left, box.left - height) > 0f
        }
        val available = near.flatMap { words(it.text) }.groupingBy { it }.eachCount().toMutableMap()
        val wanted = words(text)
        return wanted.isNotEmpty() && wanted.all { word ->
            val count = available[word] ?: 0
            if (count > 0) { available[word] = count - 1; true } else false
        }
    }

    /** A detector box this large is a block or a picture, not one line of print. */
    fun lineSized(box: OcrLine): Boolean = box.bottom - box.top <= MAX_LINE_HEIGHT && (box.right - box.left) * (box.bottom - box.top) <= MAX_LINE_AREA

    fun recovered(box: OcrLine, reading: Reading): OcrLine =
        box.copy(text = reading.text, confidence = reading.confidence, doubtful = true)

    private fun overlap(first: OcrLine, second: OcrLine): Float {
        val width = min(first.right, second.right) - max(first.left, second.left)
        val height = min(first.bottom, second.bottom) - max(first.top, second.top)
        return if (width > 0f && height > 0f) width * height else 0f
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFC).split(Regex("[\\s\\p{Z}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun words(text: String) = normalize(text).split(' ').filter { it.isNotEmpty() }
}

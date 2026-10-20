package com.facturaclara.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** Text fragment in the final upright page preview; all edges are normalized to 0..1. */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Baseline dy/dx in normalized page coordinates, not a modification of text. */
    val slope: Float = 0f,
    val confidence: Float? = null,
    /** Unconfirmed fragment kept verbatim and highlighted for review in the PDF. */
    val doubtful: Boolean = false,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(right > left && bottom > top)
        require(slope.isFinite())
        require(confidence == null || (confidence.isFinite() && confidence in 0f..1f))
    }
}

data class ScanPage(
    val index: Int,
    val imagePath: String,
    val text: String,
    val warnings: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val lines: List<OcrLine> = emptyList(),
)

data class Invoice(
    val id: String,
    val name: String,
    val createdAt: Long,
    val sourcePath: String,
    val mime: String,
    var status: String = "queued",
    var pages: List<ScanPage> = emptyList(),
    var error: String? = null,
    var pdfPath: String? = null,
    val isDesignExample: Boolean = false,
)

/** Reconstructs visual rows without changing, merging, or dropping any text fragments. */
object OcrLayout {
    /** Same row order as the PDF, preserving every recognized fragment verbatim. */
    fun text(lines: List<OcrLine>): String {
        val widths = lines.map { (it.right - it.left) / it.text.length.coerceAtLeast(1) }.sorted()
        val characterWidth = widths.getOrNull(widths.size / 2) ?: .01f
        return rows(lines).joinToString("\n") { row ->
            buildString {
                row.forEachIndexed { index, fragment ->
                    if (index > 0) {
                        val gap = fragment.left - row[index - 1].right
                        append(" ".repeat((gap / characterWidth).roundToInt().coerceIn(2, 60)))
                    }
                    append(fragment.text)
                }
            }
        }
    }

    /**
     * Visual rows in reading order. Overlaid text that does not follow the receipt's
     * baseline (a rotated sticker, vertical margin text) is laid out as its own block
     * after the receipt body, so it cannot capture the body's amounts. In the body,
     * amounts are then matched to label rows in order (see [alignAmounts]).
     */
    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        if (lines.isEmpty()) return emptyList()
        val slope = pageSlope(lines)
        val height = median(lines.map { it.bottom - it.top })
        val overlay = overlayBlock(lines, slope, height)
        if (overlay.isEmpty()) return alignAmounts(lines)
        val body = lines.filterIndexed { index, _ -> index !in overlay }
        val other = lines.filterIndexed { index, _ -> index in overlay }
        return (if (body.isEmpty()) emptyList() else alignAmounts(body)) + groupRows(other)
    }

    /**
     * Fragments of overlaid text. A rotated fragment counts only together with another
     * one at a similar angle, so a block such as a sticker is moved, never a single body
     * line that curls on curved paper. Vertical fragments count on their own.
     */
    private fun overlayBlock(lines: List<OcrLine>, pageSlope: Float, height: Float): Set<Int> {
        val vertical = lines.indices.filter { isVertical(lines[it], height) }
        val rotated = lines.indices.filter { it !in vertical && isRotated(lines[it], pageSlope, height) }
        val block = rotated.filter { index -> rotated.any { other -> other != index &&
            abs(lines[other].slope - lines[index].slope) <= .05f } }
        return (vertical + block).toSet()
    }

    /**
     * Rotated well off the page baseline. A rotated overlay must
     * both drift by more than 1.5 text heights along its width and differ clearly in
     * angle: a long body line on curved paper drifts too, but only by a small angle.
     */
    private fun isRotated(line: OcrLine, pageSlope: Float, height: Float): Boolean {
        val angle = abs(line.slope - pageSlope)
        return angle >= .12f && angle * (line.right - line.left) / height > 1.5f
    }

    private fun isVertical(line: OcrLine, height: Float): Boolean {
        val tall = line.bottom - line.top
        return tall >= 2.5f * (line.right - line.left) && tall >= 2f * height
    }

    /**
     * Groups rows, then matches every amount to at most one label row, keeping both in
     * page order (a monotonic alignment). On curved or tilted paper an amount may sit
     * closer to the neighbouring label than to its own, but amounts and labels still
     * run in the same order. The vertical offset between the two columns is estimated
     * from neighbouring matches, so it can vary slowly down the page.
     */
    private fun alignAmounts(lines: List<OcrLine>): List<List<OcrLine>> {
        val amounts = lines.filter { isAmount(it.text) }
        val labels = lines.filterNot { isAmount(it.text) }
        if (amounts.isEmpty() || labels.isEmpty()) return groupRows(lines)
        val slope = pageSlope(lines)
        val heights = lines.map { it.bottom - it.top }.sorted()
        val height = heights[heights.size / 2].takeIf { it > 0f } ?: .01f
        // Label rows are grouped without the amounts: the amounts are placed afterwards, and
        // a short amount's noisy angle must not keep a quantity apart from its description.
        val base = groupRows(labels).sortedBy { row -> row.map { deskewed(it, slope).toDouble() }.average() }
        val baseY = base.map { row -> row.map { deskewed(it, slope).toDouble() }.average() }
        val rows = base.map { it.toMutableList() }.toMutableList()
        val keys = baseY.toMutableList()
        val leftovers = mutableListOf<OcrLine>()
        // One pass per amount column (unit price, total...), so a row keeps one amount per column.
        for (column in amountColumns(amounts)) {
            val ordered = column.sortedBy { deskewed(it, slope) }
            val assignment = alignColumn(ordered, base, baseY, height)
            ordered.forEachIndexed { j, amount ->
                if (assignment[j] >= 0) rows[assignment[j]] += amount else leftovers += amount
            }
        }
        // Amounts without a label row (a line of figures only) still share rows with each other.
        groupRows(leftovers).forEach { row ->
            rows += row.toMutableList()
            keys += row.map { deskewed(it, slope).toDouble() }.average()
        }
        return rows.indices.sortedBy { keys[it] }.map { index -> rows[index].sortedBy { it.left } }
    }

    /** Amounts connected by any horizontal overlap form one column. */
    private fun amountColumns(amounts: List<OcrLine>): List<List<OcrLine>> {
        val parent = IntArray(amounts.size) { it }
        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) { parent[current] = parent[parent[current]]; current = parent[current] }
            return current
        }
        for (i in amounts.indices) for (j in i + 1 until amounts.size) {
            // The same overlap rule as row grouping: touching boxes are not one column.
            if (overlapsColumn(amounts[i], amounts[j])) {
                parent[find(i)] = find(j)
            }
        }
        return amounts.indices.groupBy { find(it) }.values.map { members -> members.map { amounts[it] } }
    }

    private fun alignColumn(amounts: List<OcrLine>, base: List<List<OcrLine>>, baseY: List<Double>, height: Float): IntArray {
        var assignment = IntArray(amounts.size) { -1 }
        var offsets = DoubleArray(base.size)
        repeat(2) {
            val costs = Array(amounts.size) { DoubleArray(base.size) }
            val residuals = Array(amounts.size) { DoubleArray(base.size) }
            amounts.forEachIndexed { j, amount ->
                base.forEachIndexed { i, row ->
                    var best = Double.MAX_VALUE
                    row.forEach { fragment ->
                        val residual = signedResidual(amount, fragment) / height
                        val cost = abs(residual - offsets[i])
                        if (cost < best) { best = cost; residuals[j][i] = residual }
                    }
                    val gap = row.minOf { fragment -> maxOf(0f, maxOf(amount.left - fragment.right, fragment.left - amount.right)) }
                    costs[j][i] = best + GAP_WEIGHT * gap
                }
            }
            assignment = monotonicMatch(costs, UNMATCHED_AMOUNT_COST)
            val matchedY = amounts.indices.filter { assignment[it] >= 0 }.map { baseY[assignment[it]] }
            val matchedResidual = amounts.indices.filter { assignment[it] >= 0 }.map { residuals[it][assignment[it]] }
            offsets = DoubleArray(base.size) { i ->
                if (matchedY.isEmpty()) 0.0 else {
                    val nearest = matchedY.indices.sortedBy { abs(matchedY[it] - baseY[i]) }.take(5)
                    medianOf(nearest.map { matchedResidual[it] })
                }
            }
        }
        return assignment
    }

    /** Strictly increasing amount-to-row assignment of minimum total cost; -1 leaves an amount alone. */
    private fun monotonicMatch(costs: Array<DoubleArray>, unmatched: Double): IntArray {
        val amounts = costs.size
        val rows = if (amounts == 0) 0 else costs[0].size
        val total = Array(amounts + 1) { DoubleArray(rows + 1) }
        val choice = Array(amounts + 1) { IntArray(rows + 1) }
        for (j in 1..amounts) {
            total[j][0] = j * unmatched
            choice[j][0] = 2
            for (i in 1..rows) {
                var best = total[j][i - 1]
                var step = 0
                val match = total[j - 1][i - 1] + costs[j - 1][i - 1]
                if (match < best) { best = match; step = 1 }
                val alone = total[j - 1][i] + unmatched
                if (alone < best) { best = alone; step = 2 }
                total[j][i] = best
                choice[j][i] = step
            }
        }
        val assignment = IntArray(amounts) { -1 }
        var j = amounts
        var i = rows
        while (j > 0) {
            if (i == 0) { j--; continue }
            when (choice[j][i]) {
                0 -> i--
                1 -> { assignment[j - 1] = i - 1; j--; i-- }
                else -> j--
            }
        }
        return assignment
    }

    /** Vertical offset of [amount] below [fragment] after the pair's baseline slope. */
    private fun signedResidual(amount: OcrLine, fragment: OcrLine): Double {
        val dx = (amount.left + amount.right - fragment.left - fragment.right) / 2.0
        val span = (amount.right - amount.left).toDouble()
        val other = (fragment.right - fragment.left).toDouble()
        val weight = span * span
        val otherWeight = other * other
        val slope = if (weight + otherWeight > 0) (amount.slope * weight + fragment.slope * otherWeight) / (weight + otherWeight) else 0.0
        return (amount.top + amount.bottom - fragment.top - fragment.bottom) / 2.0 - dx * slope
    }

    private val amountPattern = Regex("^(?:S/\\.?|US\\\$|[\$sS€])?-?(?:\\d{1,3}(?:[.,]\\d{3})+|\\d*)[.,]\\d{1,2}-?\$")

    internal fun isAmount(text: String) = amountPattern.matches(text.filterNot(Char::isWhitespace))

    private fun median(values: List<Float>): Float = medianOf(values.map { it.toDouble() }).toFloat()

    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private const val UNMATCHED_AMOUNT_COST = 1.5
    /** Per page width of horizontal gap: an amount prefers the label beside it over a far one. */
    private const val GAP_WEIGHT = 0.5

    /** Pairwise row grouping, ordered along the page baseline. */
    private fun groupRows(lines: List<OcrLine>): List<List<OcrLine>> {
        if (lines.isEmpty()) return emptyList()
        val ordered = lines.sortedWith(compareBy<OcrLine> { center(it) }.thenBy { it.left })
        val pageSlope = pageSlope(lines)
        val textHeight = median(lines.map { it.bottom - it.top })
        val groups = ordered.indices.map { mutableListOf(it) }.toMutableList()
        val groupOf = IntArray(ordered.size) { it }
        data class Pairing(val first: Int, val second: Int, val distance: Float)

        // Consider every candidate before committing a short fragment. On a tilted
        // receipt an amount can occur above its description, so a vertical scan
        // must not attach it to the preceding row while the better row is unseen.
        val pairs = buildList {
            for (first in ordered.indices) {
                for (second in first + 1 until ordered.size) {
                    val a = ordered[first]
                    val b = ordered[second]
                    val distance = normalizedDistance(a, b, pageSlope, textHeight)
                    if (!overlapsColumn(a, b) && distance <= .60f) {
                        add(Pairing(first, second, distance))
                    }
                }
            }
        }

        pairs.sortedWith(compareBy<Pairing> { it.distance }.thenBy { it.first }.thenBy { it.second })
            .forEach { pair ->
                val firstGroup = groupOf[pair.first]
                val secondGroup = groupOf[pair.second]
                if (firstGroup == secondGroup) return@forEach
                val first = groups[firstGroup]
                val second = groups[secondGroup]
                // Complete compatibility prevents a middle column from chaining
                // two different rows together. Overlapping text stays separate,
                // and every input occurrence survives even when texts are equal.
                if (first.all { a -> second.all { b ->
                    !overlapsColumn(ordered[a], ordered[b]) &&
                        normalizedDistance(ordered[a], ordered[b], pageSlope, textHeight) <= .60f
                } }) {
                    first.addAll(second)
                    second.forEach { groupOf[it] = firstGroup }
                    second.clear()
                }
            }
        return groups.filter { it.isNotEmpty() }.map { row -> row.map { ordered[it] } }
            // Order rows along the paper, not the camera's horizon: on a tilted photo a
            // short right-hand row can otherwise sort above or below its neighbours.
            .sortedBy { row -> row.map { deskewed(it, pageSlope) }.average() }
            .map { row -> row.sortedBy { it.left } }
    }

    private fun center(line: OcrLine): Float = (line.top + line.bottom) / 2f

    private fun overlapsColumn(first: OcrLine, second: OcrLine): Boolean {
        val overlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
        return overlap > minOf(first.right - first.left, second.right - second.left) * .35f
    }

    private fun normalizedDistance(first: OcrLine, second: OcrLine, pageSlope: Float, textHeight: Float): Float =
        pairDistance(first, second, pageSlope, textHeight) / minOf(first.bottom - first.top, second.bottom - second.top)

    /** Width-weighted median baseline slope of the page; long fragments dominate noisy short ones. */
    private fun pageSlope(lines: List<OcrLine>): Float {
        // Low-confidence fragments are noisy; they only vote when nothing better is left.
        val reliable = lines.filter { (it.confidence ?: 1f) >= .5f }.ifEmpty { lines }
        val weighted = reliable.map { it.slope to (it.right - it.left) }.sortedBy { it.first }
        val total = weighted.sumOf { it.second.toDouble() }
        var accumulated = 0.0
        weighted.forEach { (slope, width) -> accumulated += width; if (accumulated >= total / 2) return slope }
        return 0f
    }

    /** Vertical position projected along the page baseline to the page's horizontal center. */
    private fun deskewed(line: OcrLine, slope: Float): Float = center(line) - ((line.left + line.right) / 2f - .5f) * slope

    fun rowDistance(line: OcrLine, row: List<OcrLine>): Float {
        val distances = row.map { other -> pairDistance(line, other) }.sorted()
        return distances[distances.size / 2]
    }

    /**
     * With [pageSlope], a short fragment (under [SHORT_FRAGMENT] text heights wide) whose
     * baseline differs from the page by more than [OUTLIER_SLOPE] does not steer the pair:
     * a few pixels at the ends of a short box can tilt its reported angle far from the
     * printed line. Long fragments keep their measured angle, so a curled line still pairs.
     */
    private fun pairDistance(line: OcrLine, other: OcrLine, pageSlope: Float? = null, textHeight: Float = 0f): Float {
        val dx = (line.left + line.right - other.left - other.right) / 2f
        // Short amounts have noisy angle estimates: a few pixels at either
        // end can rotate their apparent baseline substantially. Give wider
        // fragments more geometric support, independently of text confidence.
        val span = line.right - line.left
        val otherSpan = other.right - other.left
        val weight = if (pageSlope != null && unreliableSlope(line, pageSlope, textHeight)) 0f else span * span
        val otherWeight = if (pageSlope != null && unreliableSlope(other, pageSlope, textHeight)) 0f else otherSpan * otherSpan
        val slope = if (weight + otherWeight > 0f) (line.slope * weight + other.slope * otherWeight) / (weight + otherWeight)
            else pageSlope ?: 0f
        return abs(center(line) - center(other) - dx * slope)
    }

    private fun unreliableSlope(fragment: OcrLine, pageSlope: Float, textHeight: Float) =
        abs(fragment.slope - pageSlope) > OUTLIER_SLOPE && fragment.right - fragment.left < SHORT_FRAGMENT * textHeight

    private const val OUTLIER_SLOPE = .1f
    private const val SHORT_FRAGMENT = 6f
}

package com.facturaclara.app

/** Adds isolated physical word locations missed by a complete-page detector. */
internal object IndependentCellPlan {
    enum class Source { INDEPENDENT_DETECTOR, SUPPLEMENTARY_ML }
    data class WordOccurrence(val readingIndex: Int, val wordIndex: Int, val word: OcrLine)
    data class Cell(
        val line: OcrLine,
        val source: Source,
        val detectorIndex: Int?,
        val occurrences: List<WordOccurrence> = emptyList(),
    )
    data class Plan(val cells: List<Cell>, val uncovered: List<WordOccurrence>)

    fun build(detectedCells: List<OcrLine>, wholeMl: List<List<OcrLine>>, width: Int, height: Int): Plan {
        require(width > 0 && height > 0)
        val original = detectedCells.mapIndexed { index, line -> Cell(line, Source.INDEPENDENT_DETECTOR, index) }
        val occurrences = wholeMl.flatMapIndexed { readingIndex, words -> words.mapIndexedNotNull { wordIndex, word ->
            if (word.text.isBlank()) null else WordOccurrence(readingIndex, wordIndex, word)
        } }
        // A failed complete-page detector must not silently become a page
        // reconstructed entirely from the primary reader's word boxes.
        if (detectedCells.isEmpty()) return Plan(original, occurrences)

        val candidates = occurrences.filter { occurrence ->
            // Proximity assignment includes a horizontal margin for OCR box
            // jitter. That margin is not optical coverage: a narrow quantity
            // can be assigned to a nearby description without a single source
            // pixel of the quantity appearing in the detected crop.
                detectedCells.none { intersects(occurrence.word, it) }
        }
        val visited = BooleanArray(candidates.size)
        val supplementary = mutableListOf<Cell>()
        for (start in candidates.indices) {
            if (visited[start]) continue
            val component = mutableListOf<Int>()
            val pending = ArrayDeque<Int>()
            pending.add(start)
            visited[start] = true
            while (pending.isNotEmpty()) {
                val index = pending.removeFirst()
                component += index
                for (other in candidates.indices) {
                    if (!visited[other] && intersects(candidates[index].word, candidates[other].word)) {
                        visited[other] = true
                        pending.add(other)
                    }
                }
            }
            val group = component.map { candidates[it] }
            // Never merge two words from one pass, or chain overlapping boxes
            // into a larger text region. Every pair must describe the same
            // physical location; text values play no role in that decision.
            if (group.map { it.readingIndex }.distinct().size != group.size ||
                group.indices.any { a -> (a + 1 until group.size).any { b ->
                    !samePhysicalWord(group[a].word, group[b].word)
                } }) continue
            val box = envelope(group.map { it.word })
            if (detectedCells.any { intersects(box, it) }) continue
            supplementary += Cell(box, Source.SUPPLEMENTARY_ML, null, group)
        }
        val cells = original + supplementary.sortedWith(compareBy<Cell> { it.line.top }.thenBy { it.line.left })
        val geometry = cells.map { it.line }
        return Plan(cells, occurrences.filter { owner(it.word, geometry, width, height) < 0 })
    }

    private fun owner(word: OcrLine, cells: List<OcrLine>, width: Int, height: Int): Int {
        val assignment = OpticalCellAssignment.assign(listOf(word), cells, width, height)
        val owners = assignment.indices.filter { assignment[it].isNotEmpty() }
        // Rejected supplementary groups must remain uncovered even when the
        // old proximity-only rule would attach them to a nearby detector cell.
        return owners.singleOrNull()?.takeIf { intersects(word, cells[it]) } ?: -1
    }

    /** Any positive rectangle intersection is conservatively treated as shared image content. */
    private fun intersects(a: OcrLine, b: OcrLine): Boolean =
        minOf(a.right, b.right) > maxOf(a.left, b.left) && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)

    private fun samePhysicalWord(a: OcrLine, b: OcrLine): Boolean {
        val aw = a.right - a.left
        val bw = b.right - b.left
        val ah = a.bottom - a.top
        val bh = b.bottom - b.top
        if (minOf(aw, bw) / maxOf(aw, bw) < .5f || minOf(ah, bh) / maxOf(ah, bh) < .5f) return false
        val horizontal = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val vertical = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return horizontal / minOf(aw, bw) >= .60f && vertical / minOf(ah, bh) >= .60f &&
            OcrLayout.rowDistance(a, listOf(b)) <= minOf(ah, bh) * .35f
    }

    private fun envelope(words: List<OcrLine>): OcrLine {
        val weights = words.map { (it.right - it.left).toDouble().let { width -> width * width } }
        val slope = words.indices.sumOf { words[it].slope.toDouble() * weights[it] } / weights.sum()
        return OcrLine("", words.minOf { it.left }, words.minOf { it.top }, words.maxOf { it.right },
            words.maxOf { it.bottom }, slope.toFloat())
    }
}

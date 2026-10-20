package com.facturaclara.app

import kotlin.math.abs

/** Assigns each optical word at most once using only its source geometry. */
internal object OpticalCellAssignment {
    fun assign(words: List<OcrLine>, cells: List<OcrLine>, width: Int, height: Int): List<List<OcrLine>> {
        require(width > 0 && height > 0) { "Las dimensiones de la imagen deben ser positivas." }
        val assigned = List(cells.size) { mutableListOf<OcrLine>() }
        for (word in words) {
            if (word.text.isBlank() || !validGeometry(word)) continue
            val center = (word.left + word.right) / 2
            val wordWidth = word.right - word.left
            val wordHeight = word.bottom - word.top
            val compatible = cells.indices.mapNotNull { index ->
                val cell = cells[index]
                if (!validGeometry(cell)) return@mapNotNull null
                val glyphHeight = (cell.bottom - cell.top).coerceAtLeast(.0001f)
                val vertical = OcrLayout.rowDistance(word, listOf(cell)) / maxOf(glyphHeight, wordHeight)
                if (!vertical.isFinite() || vertical > .65f) null else index to vertical
            }
            // A merged word can be centered in a wide description while also
            // covering an entire narrow quantity. Giving it to the description
            // would let that quantity appear twice after cells are assembled.
            // Use the smaller width so a narrow second cell is not hidden by the
            // merged word's much larger area. Only similarly aligned cells in
            // the word's best row count: a tall box from the next sloping row
            // can overlap horizontally without owning these glyphs.
            val materialRows = compatible.mapNotNull { (index, _) ->
                val cell = cells[index]
                val overlap = (minOf(word.right, cell.right) - maxOf(word.left, cell.left)).coerceAtLeast(0f)
                if (overlap <= minOf(wordWidth, cell.right - cell.left) * .35f) return@mapNotNull null
                // The smaller height prevents a tall detector box from making
                // an adjacent baseline appear to be part of the same row.
                OcrLayout.rowDistance(word, listOf(cell)) / minOf(wordHeight, cell.bottom - cell.top)
            }
            val bestRow = materialRows.minOrNull()
            if (bestRow != null && materialRows.count { it <= .35f && it <= bestRow + .15f } > 1) continue

            val candidates = compatible.mapNotNull { (index, vertical) ->
                val cell = cells[index]
                val glyphHeight = (cell.bottom - cell.top).coerceAtLeast(.0001f)
                val marginX = glyphHeight * height / width * .75f
                val horizontal = maxOf(0f, cell.left - center, center - cell.right) / marginX.coerceAtLeast(.0001f)
                if (horizontal > 1f) null else index to (vertical + horizontal)
            }.sortedBy { it.second }
            val first = candidates.firstOrNull() ?: continue
            if (candidates.size > 1 && abs(candidates[1].second - first.second) < .10f) continue
            assigned[first.first] += word
        }
        return assigned
    }

    private fun validGeometry(line: OcrLine): Boolean =
        line.left.isFinite() && line.top.isFinite() && line.right.isFinite() && line.bottom.isFinite() &&
            line.slope.isFinite() && line.right > line.left && line.bottom > line.top
}

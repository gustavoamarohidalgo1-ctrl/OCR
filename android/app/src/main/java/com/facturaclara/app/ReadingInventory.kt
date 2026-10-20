package com.facturaclara.app

import java.text.Normalizer

/** Geometry may reorder a page, but must retain every observed token and literal numeric group. */
internal object ReadingInventory {
    private val numbers = Regex("[+\\-−]?(?:\\p{Nd}+(?:[.,:/\\-]\\p{Nd}+)*|[.,]\\p{Nd}+)")
    fun matches(text: String, lines: List<String>, words: List<String>): Boolean {
        fun tokens(parts: List<String>) = parts.flatMap { RegionConsensus.tokens(it) }.sorted()
        fun numeric(parts: List<String>) = parts.flatMap { part ->
            numbers.findAll(Normalizer.normalize(part, Normalizer.Form.NFC)).map { it.value }.toList()
        }.sorted()
        val whole = listOf(text)
        return tokens(whole) == tokens(lines) && tokens(whole) == tokens(words) &&
            numeric(whole) == numeric(lines) && numeric(whole) == numeric(words)
    }
}

package com.facturaclara.app

import java.text.Normalizer

/** Conservative selection between actual OCR readings; never rewrites a character. */
object RegionConsensus {
    fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{M}\\p{N}]+|[^\\s\\p{Z}]")
        .findAll(Normalizer.normalize(text, Normalizer.Form.NFC)).map { it.value }.toList()

    fun includes(complete: String, partial: String): Boolean {
        val all = tokens(complete)
        var next = 0
        for (token in tokens(partial)) {
            while (next < all.size && all[next] != token) next++
            if (next == all.size) return false
            next++
        }
        return true
    }

    fun reliable(lines: List<OcrLine>): Boolean = lines.isNotEmpty() && lines.all {
        val minimum = if (it.text.any(Char::isLetterOrDigit)) .65f else .50f
        it.confidence?.let { score -> score.isFinite() && score >= minimum } == true
    }

    private fun same(a: List<OcrLine>, b: List<OcrLine>): Boolean =
        tokens(OcrLayout.text(a)) == tokens(OcrLayout.text(b))

    /** Two whole-image readings must agree without discarding text seen by the third. */
    fun whole(readings: List<List<OcrLine>>): List<OcrLine>? = readings.firstOrNull { candidate ->
        reliable(candidate) && readings.count { reliable(it) && same(candidate, it) } >= 2 &&
            readings.all { includes(OcrLayout.text(candidate), OcrLayout.text(it)) }
    }

    /** Two region readings plus whole-image support, keeping strongly confirmed anchors. */
    fun recovered(whole: List<List<OcrLine>>, regions: List<List<OcrLine>>): List<OcrLine>? {
        val confirmed = regions.filter { candidate ->
            reliable(candidate) && regions.count { reliable(it) && same(candidate, it) } >= 2 &&
                whole.any { same(candidate, it) } &&
                whole.all { includes(OcrLayout.text(candidate), OcrLayout.text(it)) }
        }
        // More supported content can recover a missed symbol. Conflicting spellings
        // have no superset and stay unresolved; confidence alone cannot pick a winner.
        return confirmed.firstOrNull { candidate ->
            confirmed.all { includes(OcrLayout.text(candidate), OcrLayout.text(it)) } &&
                regions.filter(::reliable).all { includes(OcrLayout.text(candidate), OcrLayout.text(it)) }
        }
    }
}

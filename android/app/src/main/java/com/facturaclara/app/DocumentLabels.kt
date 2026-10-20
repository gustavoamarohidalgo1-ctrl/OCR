package com.facturaclara.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Interface-only labels for documents. The stored file name, the original and the PDF never change. */
object DocumentLabels {
    private val extension = Regex("\\.(jpe?g|png|webp|heic|heif|gif|bmp|tiff?|pdf)$", RegexOption.IGNORE_CASE)
    private val separators = Regex("[\\s_.()\\[\\]\\-]+")
    private val messagingId = Regex("(?i)wa\\d+")
    // Camera, scanner and messaging prefixes that say nothing about the receipt itself.
    private val generic = setOf(
        "img", "image", "imagen", "pxl", "dsc", "dscn", "dcim", "mvimg", "photo", "foto", "screenshot",
        "captura", "pantalla", "scan", "escaneo", "whatsapp", "at", "de", "document", "documento", "pdf",
    )
    private val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

    /**
     * File name without extension, separators or timestamps, e.g. "QA-perf-1792371728848-22_mercadona.jpg" becomes
     * "QA perf 22 mercadona". Only runs of 10+ digits (epoch timestamps, RUC) are dropped, so invoice series such as
     * "F001-00000123" survive. Null when nothing descriptive is left (camera names such as "IMG_20261018_2012.jpg").
     */
    fun readableName(fileName: String): String? {
        val words = fileName.trim().replace(extension, "").split(separators)
            .filter { it.isNotEmpty() && !(it.length >= 10 && it.all(Char::isDigit)) && !messagingId.matches(it) }
        if (words.all { it.lowercase(Locale.ROOT) in generic || it.all(Char::isDigit) }) return null
        return words.joinToString(" ").replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    /** Title for a document whose file name is not descriptive: "Foto · 18 oct, 20:12", using the time it was added. */
    fun fallbackTitle(isPdf: Boolean, time: Long, use24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val date = calendar(time, zone)
        return "${if (isPdf) "PDF" else "Foto"} · ${dayMonth(date)}, ${clock(date, use24Hour)}"
    }

    /** "Hoy, 20:12", "Ayer, 8:12 p. m.", "18 oct, 20:12", or "18 oct 2025" for other years. */
    fun shortDate(time: Long, now: Long, use24Hour: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
        val date = calendar(time, zone)
        val today = calendar(now, zone)
        val yesterday = calendar(now, zone).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(date, today) -> "Hoy, ${clock(date, use24Hour)}"
            sameDay(date, yesterday) -> "Ayer, ${clock(date, use24Hour)}"
            date.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "${dayMonth(date)}, ${clock(date, use24Hour)}"
            else -> "${dayMonth(date)} ${date.get(Calendar.YEAR)}"
        }
    }

    private fun calendar(time: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = time }
    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    private fun dayMonth(date: Calendar) = "${date.get(Calendar.DAY_OF_MONTH)} ${months[date.get(Calendar.MONTH)]}"
    private fun clock(date: Calendar, use24Hour: Boolean): String {
        val hour = date.get(Calendar.HOUR_OF_DAY)
        val minute = date.get(Calendar.MINUTE)
        return if (use24Hour) String.format(Locale.ROOT, "%02d:%02d", hour, minute)
        else String.format(Locale.ROOT, "%d:%02d %s", (hour + 11) % 12 + 1, minute, if (hour < 12) "a. m." else "p. m.")
    }
}

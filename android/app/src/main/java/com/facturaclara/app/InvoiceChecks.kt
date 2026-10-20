package com.facturaclara.app

import java.math.BigDecimal
import java.util.Locale

data class CheckResult(val warnings: List<String>, val blockers: List<String>)

/** Diagnostics over printed values. A matching sum never certifies a transcription. */
object InvoiceChecks {
    private val tolerance = BigDecimal("0.02")
    private val labels = Regex(
        "(^|\\s{2,}|\\|)" +
            "((?:total\\s+)?(?:op(?:\\.\\s*|\\s+)|operaci[oó]n(?:es)?\\s+)?gravad[ao]s?|" +
            "sub\\s*total|base\\s+imponible|valor\\s+(?:de\\s+)?venta|net\\s+amount|" +
            "total\\s+(?:impuestos?|tax)|tax\\s+total|impuestos?|i\\.?g\\.?v\\.?|i\\.?v\\.?a\\.?|vat|tax|" +
            "importe\\s+total|total\\s+a\\s+pagar|monto\\s+total|grand\\s+total|amount\\s+due|total\\s+amount|total)" +
            "(?=\\s|:|$)", RegexOption.IGNORE_CASE
    )
    private val totalExclusions = Regex(
        "^\\s*[:\\-]?\\s*(?:de\\s+)?(?:igv|iva|tax|impuestos?|gravado|exonerado|inafecto|descuentos?|discount|items|art[ií]culos|units|unidades)\\b",
        RegexOption.IGNORE_CASE
    )
    private val adjustments = Regex(
        "\\b(?:descuentos?|discounts?|retenci[oó]n|retenciones|withholding|percepci[oó]n|percepciones|" +
            "anticipos?|adelantos?|prepayments?|deposits?|detracci[oó]n|detracciones|redondeo|rounding|" +
            "flete|freight|shipping|env[ií]o|propinas?|tips?|recargos?|surcharges?|" +
            "isc|icbper|otros\\s+(?:cargos|impuestos)|other\\s+(?:charges|taxes)|" +
            "impuestos?\\s+incluidos?|(?:igv|iva)\\s+incluidos?|incluye\\s+(?:igv|iva)|tax\\s+included)\\b",
        RegexOption.IGNORE_CASE
    )
    private val currencies = Regex("\\b(?:USD|PEN|EUR|GBP|MXN|COP|ARS|BRL)\\b|US\\s*\\$|S/\\.?|€|£|\\$", RegexOption.IGNORE_CASE)
    // A dot may precede an amount only as part of «S/.»; a final dot or comma
    // that starts no further digits is punctuation, not part of the number.
    private val money = Regex("(?:(?<=[Ss]/\\.)|(?<![\\w.,]))[-+]?\\s*(?:\\(\\s*)?(?:\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)*)(?:\\s*\\))?(?![.,]?\\w)")
    private val nonTaxableLabels = Regex(
        "(?:^|\\s{2,}|\\|)\\s*(?:total\\s+)?(?:op[. ]*|operaci[oó]n(?:es)?\\s+)?" +
            "(?:exonerad[ao]s?|inafect[ao]s?|no\\s+gravad[ao]s?|gratuit[ao]s?)\\b([^\\r\\n]*)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )
    private data class Candidate(val raw: String, val value: BigDecimal?, val currency: String?)

    fun inspect(text: String): CheckResult {
        val warnings = linkedSetOf<String>()
        val blockers = linkedSetOf<String>()
        val values = mutableMapOf("subtotal" to mutableListOf<Candidate>(), "taxable_base" to mutableListOf(),
            "tax" to mutableListOf(), "total" to mutableListOf(), "payable" to mutableListOf())
        val seenCurrencies = mutableSetOf<String>()
        text.lineSequence().forEach { line ->
            val matches = labels.findAll(line).toList()
            matches.forEachIndexed { index, match ->
                val label = match.groupValues[2].lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                val end = matches.getOrNull(index + 1)?.range?.first ?: line.length
                val tail = line.substring(match.range.last + 1, end)
                val kind = when {
                    label.startsWith("base") || "gravad" in label -> "taxable_base"
                    label.startsWith("sub") || label.startsWith("valor") || label.startsWith("net") -> "subtotal"
                    label.startsWith("impuesto") || label.startsWith("total imp") || label == "total tax" || label == "tax total" ||
                        label.replace(".", "") in setOf("igv", "iva", "vat", "tax") -> "tax"
                    label == "total a pagar" || label == "amount due" -> "payable"
                    else -> "total"
                }
                if (kind == "total" && totalExclusions.containsMatchIn(tail)) return@forEachIndexed
                val numbers = money.findAll(tail).filterNot {
                    tail.substring(it.range.last + 1).trimStart().startsWith("%")
                }.toList()
                // Exactly one amount, with only currency/percentage decoration, is evidence.
                if (numbers.size != 1) return@forEachIndexed
                var remainder = tail.removeRange(numbers[0].range)
                remainder = remainder.replace(Regex("\\(?\\s*\\d+(?:[.,]\\d+)?\\s*%\\s*\\)?"), "")
                remainder = currencies.replace(remainder, "")
                if (remainder.any { !it.isWhitespace() && it !in ":=-()." }) return@forEachIndexed
                val candidateCurrencies = currencies.findAll(tail).map { currency ->
                    val raw = currency.value.uppercase(Locale.ROOT).replace(" ", "")
                    when {
                        raw.startsWith("S/") -> "PEN"
                        raw == "US$" -> "USD"
                        raw == "€" -> "EUR"
                        raw == "£" -> "GBP"
                        else -> raw
                    }
                }.toSet()
                seenCurrencies += candidateCurrencies
                val raw = numbers.single().value.trim()
                val parsed = parseAmount(raw)
                if (parsed == null) {
                    warnings += "El importe «$raw» tiene separadores ambiguos o un formato no reconocido; no se interpreta automáticamente."
                }
                values.getValue(kind) += Candidate(raw, parsed, candidateCurrencies.singleOrNull())
            }
        }
        val multipleCurrencies = seenCurrencies.size > 1
        if (multipleCurrencies) warnings += "Se encontraron importes con distintas monedas; no se comparan automáticamente."
        val hasAdjustments = adjustments.containsMatchIn(text)
        // Esta app distingue el total del comprobante del saldo por pagar:
        // un anticipo puede reducir el segundo sin contradecir el primero.
        val roles = if (hasAdjustments) listOf(values.getValue("total"), values.getValue("payable"))
            else listOf(values.getValue("total") + values.getValue("payable"))
        val contradictoryTotals = roles.any { candidates ->
            candidates.groupBy { candidate ->
                candidate.currency ?: seenCurrencies.singleOrNull() ?: if (seenCurrencies.isEmpty()) "unspecified" else null
            }.any { (currency, sameCurrency) ->
                currency != null && sameCurrency.all { it.value != null } &&
                    sameCurrency.mapNotNull { it.value?.stripTrailingZeros() }.distinct().size > 1
            }
        }
        if (contradictoryTotals) {
            blockers += "Se leyeron importes distintos para el mismo tipo de total y moneda. Vuelve a capturar el documento completo con mayor claridad."
        }
        if (hasAdjustments) {
            warnings += "El documento incluye ajustes u otros impuestos; no se aplica la comprobación simple de subtotal más impuesto."
        }
        if (!hasAdjustments && !multipleCurrencies) {
            val single = values.mapValues { (_, candidates) ->
                if (candidates.isEmpty() || candidates.any { it.value == null }) null
                else candidates.mapNotNull { it.value?.stripTrailingZeros() }.distinct().singleOrNull()
            }
            val nonTaxableAmounts = nonTaxableLabels.findAll(text).any { match ->
                val amounts = money.findAll(match.groupValues[1]).map { parseAmount(it.value.trim()) }.toList()
                amounts.isEmpty() || amounts.any { it == null || it.compareTo(BigDecimal.ZERO) != 0 }
            }
            // Base imponible y Op. Gravada son solo la parte gravada. Si la boleta trae
            // otras categorías, esta app exige un subtotal completo para comprobar la suma.
            val subtotal = single["subtotal"] ?: single["taxable_base"].takeUnless { nonTaxableAmounts }
            val partialBase = single["subtotal"] == null && single["taxable_base"] != null && nonTaxableAmounts
            if (partialBase) {
                warnings += "La base imponible no representa todo el documento: hay operaciones no gravadas o sin importe claro. No se aplica la suma simple al total."
            }
            val tax = single["tax"]
            val total = single["total"] ?: single["payable"]
            if (subtotal != null && tax != null && total != null) {
                if ((subtotal + tax - total).abs() > tolerance) {
                    blockers += "El subtotal más el impuesto no coincide con el total (diferencia mayor a 0,02). Vuelve a capturar los importes con mayor claridad."
                }
            } else if (!partialBase) {
                // A skipped check must be visible: it never certifies the amounts.
                val missing = listOfNotNull("subtotal".takeIf { subtotal == null }, "impuesto".takeIf { tax == null },
                    "total".takeIf { total == null })
                val names = if (missing.size == 1) missing.single() else missing.dropLast(1).joinToString(", ") + " ni " + missing.last()
                warnings += "No se comprobó que el subtotal más el impuesto coincida con el total: no se encontró un importe claro de $names. Revisa esos importes en la imagen."
            }
        }
        return CheckResult(warnings.toList(), blockers.toList())
    }

    private fun parseAmount(value: String): BigDecimal? {
        var raw = value.trim().replace('\u00a0', ' ').replace('\u202f', ' ')
        var negative = raw.startsWith('(') && raw.endsWith(')')
        if (negative) raw = raw.substring(1, raw.length - 1).trim()
        if (raw.startsWith('+') || raw.startsWith('-')) {
            if (negative) return null
            negative = raw.startsWith('-')
            raw = raw.substring(1).trim()
        }
        if (!raw.matches(Regex("\\d[\\d., ]*"))) return null
        if (' ' in raw) {
            if (!raw.matches(Regex("\\d{1,3}(?: \\d{3})+(?:[.,]\\d{1,2})?"))) return null
            raw = raw.replace(" ", "")
        }
        val normalized = when {
            '.' in raw && ',' in raw -> {
                val decimal = if (raw.lastIndexOf('.') > raw.lastIndexOf(',')) '.' else ','
                val grouping = if (decimal == '.') ',' else '.'
                if (!raw.matches(Regex("\\d{1,3}(?:${Regex.escape(grouping.toString())}\\d{3})+${Regex.escape(decimal.toString())}\\d{1,2}"))) return null
                raw.replace(grouping.toString(), "").replace(decimal, '.')
            }
            '.' in raw || ',' in raw -> {
                val separator = if ('.' in raw) '.' else ','
                val chunks = raw.split(separator)
                when {
                    chunks.size == 2 && chunks[1].length in 1..2 -> raw.replace(separator, '.')
                    chunks.size > 2 && chunks[0].length in 1..3 && chunks.drop(1).all { it.length == 3 } -> chunks.joinToString("")
                    else -> return null
                }
            }
            else -> raw
        }
        val amount = normalized.toBigDecimalOrNull() ?: return null
        return if (negative) amount.negate() else amount
    }
}

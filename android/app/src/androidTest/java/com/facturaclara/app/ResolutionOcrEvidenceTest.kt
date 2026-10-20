package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/** Measured synthetic OCR diagnostic; neither output is assumed to be correct. */
@RunWith(AndroidJUnit4::class)
class ResolutionOcrEvidenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recordsRealOcrForOldAndNewPhotoDecodingAgainstKnownPrintedText() {
        val expected = listOf(
            "MUESTRA FICTICIA PARA MEDIR LECTURA",
            "COMERCIAL DE PRUEBA S.A.C.",
            "RUC: 20000000001",
            "BOLETA B001-00001248",
            "Fecha: 17/10/2026",
            "Cliente: María Torres Salazar",
            "Dirección: Avenida Los Álamos 123",
            "Moneda: SOLES",
            "DETALLE DE PRODUCTOS",
            "A001 Papel bond A4 paquete de 500 hojas - Cantidad: 2",
            "A002 Archivador de palanca tamaño oficio - Cantidad: 4",
            "A003 Cuaderno cuadriculado de 100 hojas - Cantidad: 3",
            "A004 Bolígrafo azul caja de 12 unidades - Cantidad: 5",
            "A005 Carpeta manila tamaño oficio - Cantidad: 6",
            "A006 Lápiz de grafito número 2 - Cantidad: 8",
            "A007 Sobre blanco tamaño carta - Cantidad: 20",
            "A008 Cinta adhesiva transparente - Cantidad: 9",
            "A009 Regla plástica de 30 centímetros - Cantidad: 7",
            "A010 Tijera de acero inoxidable - Cantidad: 11",
            "Subtotal: S/ 100.00",
            "IGV: S/ 18.00",
            "TOTAL: S/ 118.00",
            "SIN VALOR TRIBUTARIO",
            "Documento ficticio para comparar resolución y errores de lectura.",
        ).joinToString("\n")
        val source = File(context.cacheDir, "resolution-ocr-source.png")
        try {
            val image = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 26f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                expected.lines().forEachIndexed { index, line ->
                    canvas.drawText(line, 160f, 180f + index * 105f, paint)
                }
                source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }

            val budget = 10_000_000L
            val plan = ImageDecodeSizing.plan(4000, 3000, budget, 12000)
            val readings = JSONArray()
            OfflineOcr().use { engine ->
                for (isNew in listOf(false, true)) {
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(source.absolutePath,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inSampleSize = if (isNew) plan.sampleSize else 2
                            inScaled = isNew && plan.scales
                            if (isNew) {
                                inDensity = plan.sourceDensity
                                inTargetDensity = plan.targetDensity
                            }
                        }))
                    try {
                        bitmap.density = Bitmap.DENSITY_NONE
                        val pixels = bitmap.width.toLong() * bitmap.height
                        assertTrue(pixels <= budget)
                        if (isNew) assertTrue(pixels > 9_990_000L)
                        val reading = engine.recognize(bitmap)
                        val canonicalExpected = canonical(expected)
                        val canonicalActual = canonical(reading.text)
                        val edits = editDistance(canonicalExpected, canonicalActual)
                        val comparison = OcrConsistency.compare(expected, reading.text)
                        readings.put(JSONObject().apply {
                            put("decoding", if (isNew) "density-scaled" else "previous-power-of-two")
                            put("width", bitmap.width)
                            put("height", bitmap.height)
                            put("pixels", pixels)
                            put("text", reading.text)
                            put("expected_character_count", canonicalExpected.length)
                            put("actual_character_count", canonicalActual.length)
                            put("character_edits", edits)
                            put("character_error_rate", edits.toDouble() / canonicalExpected.length)
                            put("exact_after_whitespace_normalization", canonicalExpected == canonicalActual)
                            put("content_tokens_match", comparison.contentMatches)
                            put("numeric_tokens_match", comparison.numbersMatch)
                            put("warnings", JSONArray(reading.warnings))
                            put("blockers", JSONArray(reading.blockers))
                        })
                    } finally { bitmap.recycle() }
                }
            }
            val report = JSONObject().apply {
                put("fixture", "Known synthetic text rendered at 26px on a 4000x3000 PNG")
                put("source_width", 4000)
                put("source_height", 3000)
                put("expected_text", expected)
                put("ocr_pixel_budget", budget)
                put("measurement", "Levenshtein character edits after NFC and collapsed whitespace")
                put("scope", "One primary OCR pass for each decode. Does not exercise the production two-pass export gate.")
                put("note", "Synthetic evidence only; this is not a measurement of accuracy on real receipts or a guarantee.")
                put("readings", readings)
            }
            File(context.filesDir, "resolution-ocr-evidence.json").writeText(report.toString(2), Charsets.UTF_8)
            assertTrue(readings.length() == 2)
        } finally { source.delete() }
    }

    private fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace(Regex("[\\s\\p{Z}]+"), " ").trim()

    private fun editDistance(expected: String, actual: String): Int {
        var previous = IntArray(actual.length + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.length + 1)
            current[0] = i + 1
            for (j in actual.indices) {
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (expected[i] == actual[j]) 0 else 1)
            }
            previous = current
        }
        return previous[actual.length]
    }
}

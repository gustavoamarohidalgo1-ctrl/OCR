package com.facturaclara.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/** Test-only fictitious receipt and deterministic geometry. This is not OCR output. */
object DemoReceiptFactory {
    data class Fixture(val text: String, val lines: List<OcrLine>)

    private data class Product(val description: String, val shortName: String, val quantity: Int,
                               val unit: String, val priceCents: Int)
    private data class Run(val text: String, val x: Float, val baseline: Float,
                           val size: Float = 35f, val bold: Boolean = false,
                           val color: Int = Color.BLACK)
    private data class Plan(val height: Int, val runs: List<Run>, val tableTop: Float,
                            val tableBottom: Float, val totalsTop: Float, val productCount: Int)

    private val products = listOf(
        Product("Papel bond A4, paquete de 500 hojas", "Papel bond A4", 2, "UND", 3000),
        Product("Archivador de palanca tamaño oficio", "Archivador oficio", 4, "UND", 1000),
        Product("Cuaderno cuadriculado A4 de 100 hojas", "Cuaderno cuadriculado A4", 3, "UND", 1800),
        Product("Bolígrafo azul, caja de 12 unidades", "Bolígrafo azul", 5, "CJA", 900),
        Product("Carpeta manila tamaño oficio", "Carpeta manila oficio", 6, "UND", 500)
    )
    private const val WIDTH = 1800
    private const val LEFT = 90f
    private const val RIGHT = 1710f
    private const val HEADER_HEIGHT = 88f
    private const val ROW_HEIGHT = 100f
    private const val TABLE_TOP = 675f

    /** A high-resolution, legible sample with the shape of a commercial receipt. */
    fun create(): Bitmap {
        val plan = plan(5)
        val bitmap = Bitmap.createBitmap(WIDTH, plan.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(202, 210, 216); strokeWidth = 2f }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 62, 72); strokeWidth = 3f; style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 243, 245) }
        canvas.drawRoundRect(1090f, 80f, RIGHT, 385f, 8f, 8f, outline)
        canvas.drawLine(LEFT, 425f, RIGHT, 425f, rule)
        canvas.drawRect(LEFT, plan.tableTop, RIGHT, plan.tableTop + HEADER_HEIGHT, fill)
        canvas.drawLine(LEFT, plan.tableTop, RIGHT, plan.tableTop, rule)
        canvas.drawLine(LEFT, plan.tableTop + HEADER_HEIGHT, RIGHT, plan.tableTop + HEADER_HEIGHT, rule)
        for (row in 1..plan.productCount) {
            val y = plan.tableTop + HEADER_HEIGHT + ROW_HEIGHT * row
            canvas.drawLine(LEFT, y, RIGHT, y, rule)
        }
        canvas.drawLine(LEFT, plan.tableTop, LEFT, plan.tableBottom, rule)
        canvas.drawLine(RIGHT, plan.tableTop, RIGHT, plan.tableBottom, rule)
        listOf(230f, 365f, 1190f, 1450f).forEach { x -> canvas.drawLine(x, plan.tableTop, x, plan.tableBottom, rule) }
        canvas.drawRect(1060f, plan.totalsTop + 163f, RIGHT, plan.totalsTop + 260f, fill)
        canvas.drawLine(1060f, plan.totalsTop + 163f, RIGHT, plan.totalsTop + 163f, rule)
        canvas.drawLine(LEFT, plan.height - 184f, RIGHT, plan.height - 184f, rule)
        plan.runs.forEach { run -> canvas.drawText(run.text, run.x, run.baseline, paint(run)) }
        return bitmap
    }

    /** Same text and measured boxes used by create(), without a recognition step. */
    fun fixture(productCount: Int = 5): Fixture {
        val plan = plan(productCount)
        val ordered = plan.runs.sortedWith(compareBy<Run> { it.baseline }.thenBy { it.x })
        val lines = ordered.map { run ->
            val paint = paint(run)
            val metrics = paint.fontMetrics
            OcrLine(
                text = run.text,
                left = (run.x / WIDTH).coerceIn(0f, 1f),
                top = ((run.baseline + metrics.ascent) / plan.height).coerceIn(0f, 1f),
                right = ((run.x + paint.measureText(run.text)) / WIDTH).coerceIn(0f, 1f),
                bottom = ((run.baseline + metrics.descent) / plan.height).coerceIn(0f, 1f)
            )
        }
        return Fixture(ordered.joinToString("\n") { it.text }, lines)
    }

    private fun plan(productCount: Int): Plan {
        require(productCount in 1..200) { "La muestra admite de 1 a 200 productos." }
        val runs = mutableListOf<Run>()
        fun add(text: String, x: Float, y: Float, size: Float = 35f, bold: Boolean = false, color: Int = Color.BLACK) {
            runs.add(Run(text, x, y, size, bold, color))
        }
        fun right(text: String, edge: Float, y: Float, size: Float = 35f, bold: Boolean = false) {
            val run = Run(text, 0f, y, size, bold)
            runs.add(run.copy(x = edge - paint(run).measureText(text)))
        }
        fun centered(text: String, x: Float, y: Float, size: Float, bold: Boolean = false) {
            val run = Run(text, 0f, y, size, bold)
            runs.add(run.copy(x = x - paint(run).measureText(text) / 2f))
        }
        add("COMERCIAL EJEMPLO S.A.C.", LEFT, 155f, 49f, true)
        add("Suministros de oficina y papelería", LEFT, 220f, 35f)
        add("RUC: 20000000001", LEFT, 280f, 38f, true)
        add("Av. Los Álamos 123, local 04", LEFT, 337f, 34f)
        add("Lima, Perú", LEFT, 389f, 34f)
        centered("BOLETA DE VENTA", 1400f, 153f, 40f, true)
        centered("ELECTRÓNICA", 1400f, 216f, 38f, true)
        centered("B001-00001248", 1400f, 302f, 47f, true)
        add("Cliente: María Torres Salazar", LEFT, 497f, 36f)
        add("Fecha: 17/10/2026", 1240f, 497f, 36f)
        add("DNI: 00000000", LEFT, 555f, 35f)
        add("Moneda: SOLES (PEN)", 1240f, 555f, 35f)
        add("Dirección: Jr. Las Flores 456, Lima", LEFT, 613f, 35f)

        add("Cant.", 119f, TABLE_TOP + 57f, 34f, true)
        add("U.M.", 260f, TABLE_TOP + 57f, 34f, true)
        add("Descripción", 391f, TABLE_TOP + 57f, 34f, true)
        right("P. unit.", 1416f, TABLE_TOP + 57f, 34f, true)
        right("Importe", 1676f, TABLE_TOP + 57f, 34f, true)
        var subtotalCents = 0
        for (index in 0 until productCount) {
            val product = products[index % products.size]
            val baseline = TABLE_TOP + HEADER_HEIGHT + ROW_HEIGHT * index + 62f
            val description = if (productCount <= 5) product.description else "Artículo ${(index + 1).toString().padStart(3, '0')} - ${product.shortName}"
            add(product.quantity.toString(), 135f, baseline, 36f)
            add(product.unit, 259f, baseline, 34f)
            add(description, 391f, baseline, 34f)
            right(money(product.priceCents), 1416f, baseline, 36f)
            val amount = product.quantity * product.priceCents
            right(money(amount), 1676f, baseline, 36f)
            subtotalCents += amount
        }
        val taxCents = (subtotalCents * 18 + 50) / 100
        val totalCents = subtotalCents + taxCents
        val tableBottom = TABLE_TOP + HEADER_HEIGHT + productCount * ROW_HEIGHT
        val totalsTop = tableBottom + 64f
        add("Forma de pago: Contado", LEFT, totalsTop + 43f, 36f)
        add("Medio de pago: Efectivo", LEFT, totalsTop + 105f, 36f)
        add("Operación gravada", 1082f, totalsTop + 43f, 35f)
        right("S/ ${money(subtotalCents)}", 1676f, totalsTop + 43f, 36f)
        add("IGV (18%)", 1082f, totalsTop + 113f, 35f)
        right("S/ ${money(taxCents)}", 1676f, totalsTop + 113f, 36f)
        add("TOTAL", 1082f, totalsTop + 225f, 42f, true)
        right("S/ ${money(totalCents)}", 1676f, totalsTop + 225f, 44f, true)
        val height = (totalsTop + 560f).toInt()
        add("Gracias por su compra.", LEFT, height - 245f, 35f)
        centered("MUESTRA FICTICIA - SIN VALOR TRIBUTARIO", WIDTH / 2f, height - 119f, 32f, true)
        centered("Documento de ejemplo para probar la aplicación. No acredita una operación real.", WIDTH / 2f, height - 65f, 30f)
        return Plan(height, runs, TABLE_TOP, tableBottom, totalsTop, productCount)
    }

    private fun money(cents: Int): String = String.format(Locale.US, "%d.%02d", cents / 100, cents % 100)
    private fun paint(run: Run) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = run.color
        textSize = run.size
        typeface = Typeface.create("sans-serif", if (run.bold) Typeface.BOLD else Typeface.NORMAL)
    }
}

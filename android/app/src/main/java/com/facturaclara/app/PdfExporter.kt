package com.facturaclara.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.floor

/** Rebuilds a clean receipt using recognized text and columns; never fabricates invoice fields. */
class PdfExporter(private val context: Context) {
    private val ink = Color.rgb(26, 44, 49)
    private val teal = Color.rgb(22, 86, 88)
    private val muted = Color.rgb(102, 119, 124)
    private val rule = Color.rgb(219, 229, 229)
    private val detailPaint = textPaint(8f, muted)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rule; strokeWidth = 0.6f }
    private val reviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 226, 110) }
    private val cautionPaint = textPaint(8f, Color.rgb(153, 92, 0), true)
    private fun textPaint(size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        fontFeatureSettings = "'liga' 0, 'clig' 0"
    }

    /** A confirmed (ready) document never carries blockers; a review document shows them highlighted. */
    private fun validate(invoice: Invoice, allowReview: Boolean) {
        require(invoice.pages.isNotEmpty()) { "No hay páginas para exportar." }
        require(allowReview || invoice.pages.all { it.blockers.isEmpty() }) { "Se necesita una nueva captura antes de generar el PDF." }
        require(invoice.pages.all { it.text.isNotBlank() }) { "Hay una página sin texto; vuelve a capturarla." }
    }

    fun generate(invoice: Invoice, allowReview: Boolean = invoice.status == "review"): File {
        validate(invoice, allowReview)
        require(invoice.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Identificador de documento no válido." }
        val footer = PdfTextLayout.wrapText("Origen: ${invoice.name}", CONTENT_WIDTH - 80f, detailPaint::measureText)
        val footerTop = PAGE_HEIGHT - MARGIN - (footer.size + 1) * 11f - 7f
        require(footerTop > CONTENT_TOP + 60f) { "El nombre del documento es demasiado largo para el pie de página." }
        val plans = planPages(invoice, footerTop - 20f)
        val directory = File(context.filesDir, "documents/${invoice.id}")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("No se pudo crear la carpeta del PDF.")
        val target = File(directory, "result.pdf")
        val temporary = File.createTempFile("result-", ".pdf.tmp", directory)
        try {
            val reviewSources = invoice.pages.indices.filter { invoice.pages[it].blockers.isNotEmpty() }.map { it + 1 }.toSet()
            // Only promise yellow highlights on pages that actually have some.
            val highlighted = reviewSources.filter { source ->
                ReceiptStructure.rows(invoice.pages[source - 1]).any { row -> row.cells.any { it.doubtful } }
            }.toSet()
            constructPdf(temporary, plans, footer, footerTop, invoice.isDesignExample, reviewSources, highlighted)
            if (!temporary.renameTo(target)) throw IOException("No se pudo guardar el PDF terminado.")
            return target
        } finally { temporary.delete() }
    }

    fun write(invoice: Invoice, output: Uri) {
        require(invoice.status == "ready" || invoice.status == "review") { "El documento debe terminar de procesarse antes de exportar." }
        validate(invoice, invoice.status == "review")
        // Regenerate on export so receipts saved by an older app receive the current layout.
        val source = generate(invoice)
        if (output.scheme == "file" && output.path?.let { File(it).canonicalFile } == source.canonicalFile) return
        val destination = context.contentResolver.openOutputStream(output, "wt")
            ?: throw IOException("No se pudo abrir el archivo de destino.")
        destination.use { stream -> source.inputStream().use { it.copyTo(stream) }; stream.flush() }
    }

    private data class DrawCell(val lines: List<String>, val x: Float, val width: Float, val paint: Paint, val rightAligned: Boolean,
        val doubtful: Boolean = false)
    private data class DrawRow(val cells: List<DrawCell>, val kind: ReceiptStructure.Kind, val lineHeight: Float, val padding: Float) {
        val count get() = cells.maxOfOrNull { it.lines.size } ?: 1
        val height get() = count * lineHeight + 2f * padding
        fun part(start: Int, end: Int) = copy(cells = cells.map { it.copy(lines = it.lines.drop(start).take(end - start)) })
    }
    private data class PositionedRow(val row: DrawRow, val y: Float, val alternate: Boolean)
    /** A receipt page as rebuilt rows, or a full sheet ([sheet]) drawn in its original arrangement. */
    private data class PagePlan(val sourceIndex: Int, val rows: MutableList<PositionedRow> = mutableListOf(),
        val sheet: ScanPage? = null, val sheetAspect: Float = 0f)

    private fun makeRow(row: ReceiptStructure.Row): DrawRow {
        val kind = row.kind
        val table = kind == ReceiptStructure.Kind.ITEM || kind == ReceiptStructure.Kind.TABLE_HEADER
        val header = kind == ReceiptStructure.Kind.TABLE_HEADER
        val total = kind == ReceiptStructure.Kind.TOTAL
        val heading = kind == ReceiptStructure.Kind.HEADING
        val padding = when { header -> 9f; table -> 6f; total -> 11f; heading -> 8f; else -> 5f }
        var cells = row.cells
        var numeric = List(cells.size) { false }
        var aligned = false
        if (table && row.columns.isNotEmpty()) {
            val groups = row.columns.map { mutableListOf<ReceiptStructure.Cell>() }
            val safe = if (header && cells.size == groups.size) {
                cells.forEachIndexed { i, cell -> groups[i].add(cell) }; true
            } else {
                cells.all { cell ->
                    // Descriptions can extend into the empty gap after a short column label.
                    // Assign by their starting position, then wrap inside the destination cell.
                    val index = ReceiptStructure.columnIndex(cell, row.columns)
                    if (index < 0) false else {
                        val column = row.columns[index]
                        if (ReceiptStructure.isNumericValue(cell.text) && cell.left < column.left - .035f) false
                        else if (cell.right > column.right + .035f && cells.size < row.columns.size - 1) false
                        else { groups[index].add(cell); true }
                    }
                }
            }
            if (safe) {
                cells = row.columns.mapIndexed { i, column ->
                    ReceiptStructure.Cell(groups[i].joinToString(" ") { it.text }, column.left, column.right, groups[i].any { it.doubtful })
                }
                numeric = row.columns.map { it.numeric }
                aligned = true
            }
        }
        val drawCells = cells.mapIndexed { i, cell ->
            val sourceRight = if (aligned) cell.right else cells.getOrNull(i + 1)?.left ?: 1f
            val size = when {
                header -> 9f
                total -> 13f
                heading && i == 0 -> 17f
                heading -> 11f
                kind == ReceiptStructure.Kind.NOTE -> 9f
                else -> 10f
            }
            val paint = textPaint(size, if (header) Color.WHITE else if (total) teal else ink,
                header || total || heading)
            val monetary = !aligned && i == cells.lastIndex &&
                Regex("^(?:S/\\.?|US\\$|\\$|PEN|USD|EUR)?\\s*[-+]?\\d+(?:[.,]\\d+)*$").matches(cell.text.trim())
            var x = MARGIN + cell.left * CONTENT_WIDTH + 8f
            val edge = MARGIN + sourceRight * CONTENT_WIDTH - 8f
            if (monetary) {
                val previousEdge = cells.getOrNull(i - 1)?.right?.let { MARGIN + it * CONTENT_WIDTH + 10f } ?: MARGIN + 8f
                x = minOf(x, maxOf(previousEdge, edge - paint.measureText(cell.text) - 1f))
            }
            val width = (edge - x).coerceAtLeast(4f)
            val lines = PdfTextLayout.wrapText(cell.text, width, paint::measureText)
            DrawCell(lines, x, width, paint, (numeric.getOrElse(i) { false } && aligned) || monetary, cell.doubtful)
        }
        // Malformed/overcrowded geometry must not force a glyph into a narrower cell.
        // Keep the full row in readable order rather than clipping or overlapping it.
        if (drawCells.any { cell -> cell.lines.any { cell.paint.measureText(it) > cell.width + .1f } } ||
            drawCells.indices.any { i -> numeric.getOrElse(i) { false } && drawCells[i].lines.size > 1 }) {
            val paint = textPaint(if (header) 9f else 10f, if (header) Color.WHITE else ink, header || total)
            val text = row.cells.joinToString("  ") { it.text }
            return DrawRow(listOf(DrawCell(PdfTextLayout.wrapText(text, CONTENT_WIDTH - 16f, paint::measureText),
                MARGIN + 8f, CONTENT_WIDTH - 16f, paint, false, row.cells.any { it.doubtful })), kind, 14f, padding)
        }
        val height = (drawCells.maxOfOrNull { it.paint.textSize } ?: 10f) * 1.4f
        return DrawRow(drawCells, kind, height, padding)
    }

    private fun planPages(invoice: Invoice, contentBottom: Float): List<PagePlan> {
        val plans = mutableListOf<PagePlan>()
        invoice.pages.forEachIndexed { sourcePosition, source ->
            // An A4 or letter invoice keeps its own arrangement on a single output page. Its
            // fragments are drawn instead of the text, so both must hold the same characters.
            val aspect = sourceAspect(source)
            if (aspect != null && ReceiptStructure.hasGeometry(source) && SheetLayout.isSheet(source.lines)) {
                plans += PagePlan(sourcePosition + 1, sheet = source, sheetAspect = aspect)
                return@forEachIndexed
            }
            var page = PagePlan(sourcePosition + 1).also(plans::add)
            var y = CONTENT_TOP
            var tableHeaders = mutableListOf<DrawRow>()
            var itemIndex = 0
            fun nextPage(repeatTable: Boolean) {
                page = PagePlan(sourcePosition + 1).also(plans::add)
                y = CONTENT_TOP
                if (repeatTable && tableHeaders.sumOf { it.height.toDouble() } < (contentBottom - CONTENT_TOP) / 3)
                    tableHeaders.forEach { page.rows.add(PositionedRow(it, y, false)); y += it.height }
            }
            val rows = ReceiptStructure.rows(source).map(::makeRow)
            rows.forEachIndexed { index, row ->
                val isItem = row.kind == ReceiptStructure.Kind.ITEM
                if (row.kind == ReceiptStructure.Kind.NOTE && (index == 0 || rows[index - 1].kind != ReceiptStructure.Kind.NOTE)) {
                    val notes = rows.drop(index).takeWhile { it.kind == ReceiptStructure.Kind.NOTE }
                    val noteHeight = notes.sumOf { it.height.toDouble() }.toFloat()
                    if (noteHeight < (contentBottom - CONTENT_TOP) / 3 && y + noteHeight > contentBottom && page.rows.isNotEmpty())
                        nextPage(false)
                }
                if (row.kind == ReceiptStructure.Kind.TABLE_HEADER) {
                    if (index == 0 || rows[index - 1].kind != ReceiptStructure.Kind.TABLE_HEADER) tableHeaders.clear()
                    tableHeaders.add(row)
                    // Keep column labels with the first product when there is room on a fresh page.
                    val next = rows.getOrNull(index + 1)?.height ?: 0f
                    if (y + row.height + minOf(next, 90f) > contentBottom && page.rows.isNotEmpty()) nextPage(false)
                    y += if (page.rows.isEmpty()) 0f else 8f
                }
                if (row.kind == ReceiptStructure.Kind.TOTAL) y += 7f
                val alternate = isItem && itemIndex++ % 2 == 1
                if (y + row.height > contentBottom && row.height <= contentBottom - CONTENT_TOP && page.rows.isNotEmpty()) nextPage(isItem)
                var start = 0
                while (start < row.count) {
                    val remaining = floor((contentBottom - y - row.padding * 2) / row.lineHeight).toInt()
                    if (remaining < 1) { nextPage(isItem); continue }
                    val end = minOf(row.count, start + remaining)
                    val fragment = row.part(start, end)
                    page.rows.add(PositionedRow(fragment, y, alternate))
                    y += fragment.height
                    start = end
                    if (start < row.count) nextPage(isItem)
                }
            }
        }
        return plans
    }

    /** Width / height of the page image the geometry refers to, or null when unknown. */
    private fun sourceAspect(page: ScanPage): Float? {
        if (page.lines.isEmpty() || page.imagePath.isBlank()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(page.imagePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else null
    }

    /**
     * Draws every recognized fragment at its place on the source sheet, scaled into the
     * content area, in reading order so copied text follows the rows. Unconfirmed
     * fragments get the same yellow highlight as in the rebuilt layout.
     */
    private fun drawSheet(canvas: Canvas, page: ScanPage, aspect: Float, bottom: Float) {
        val area = SheetLayout.fit(aspect, SheetLayout.Box(MARGIN, CONTENT_TOP, CONTENT_WIDTH, bottom - CONTENT_TOP))
        OcrLayout.rows(page.lines).flatten().forEach { line ->
            val left = area.left + line.left * area.width
            val top = area.top + line.top * area.height
            val width = (line.right - line.left) * area.width
            val height = (line.bottom - line.top) * area.height
            if (height >= VERTICAL_ASPECT * width && line.text.trim().length >= 3) {
                // Margin text printed sideways. The boxes do not say which way it runs; it
                // is drawn bottom to top, the usual direction, along its own box.
                canvas.save()
                canvas.rotate(-90f, left, top + height)
                drawFitted(canvas, line, left, top + height, height, width)
                canvas.restore()
            } else drawFitted(canvas, line, left, top, width, height)
        }
    }

    /** Draws a fragment on one line, as large as its box allows. */
    private fun drawFitted(canvas: Canvas, line: OcrLine, left: Float, top: Float, width: Float, height: Float) {
        val paint = textPaint((height * .85f).coerceIn(2.5f, 30f))
        val measured = paint.measureText(line.text)
        if (measured > width * 1.02f && measured > 0f) paint.textSize = (paint.textSize * width / measured).coerceAtLeast(2.5f)
        val metrics = paint.fontMetrics
        val baseline = top + height / 2f - (metrics.ascent + metrics.descent) / 2f
        if (line.doubtful) canvas.drawRect(left - .5f, baseline + metrics.ascent, left + paint.measureText(line.text) + .5f,
            baseline + metrics.descent, reviewPaint)
        canvas.drawText(line.text, left, baseline, paint)
    }

    private fun drawRow(canvas: Canvas, position: PositionedRow) {
        val row = position.row
        val y = position.y
        val header = row.kind == ReceiptStructure.Kind.TABLE_HEADER
        val item = row.kind == ReceiptStructure.Kind.ITEM
        val total = row.kind == ReceiptStructure.Kind.TOTAL
        when {
            header -> { fillPaint.color = teal; canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + row.height, fillPaint) }
            total -> { fillPaint.color = Color.rgb(231, 243, 242); canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + row.height, 4f, 4f, fillPaint) }
            position.alternate -> { fillPaint.color = Color.rgb(246, 249, 249); canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + row.height, fillPaint) }
        }
        row.cells.forEach { cell ->
            cell.lines.forEachIndexed { index, text ->
                val x = if (cell.rightAligned) cell.x + cell.width - cell.paint.measureText(text) else cell.x
                val baseline = y + row.padding - cell.paint.fontMetrics.ascent + index * row.lineHeight
                var paint = cell.paint
                if (cell.doubtful && text.isNotBlank()) {
                    val metrics = cell.paint.fontMetrics
                    canvas.drawRect(x - 1.5f, baseline + metrics.ascent - .5f, x + cell.paint.measureText(text) + 1.5f,
                        baseline + metrics.descent + .5f, reviewPaint)
                    // White header text would be unreadable on the highlight.
                    if (header) paint = Paint(cell.paint).apply { color = ink }
                }
                canvas.drawText(text, x, baseline, paint)
            }
        }
        if (item) canvas.drawLine(MARGIN, y + row.height, PAGE_WIDTH - MARGIN, y + row.height, rulePaint)
    }

    private fun constructPdf(file: File, plans: List<PagePlan>, footer: List<String>, footerTop: Float, designExample: Boolean,
        reviewSources: Set<Int>, highlighted: Set<Int>) {
        val document = PdfDocument()
        try {
            plans.forEachIndexed { index, plan ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    fillPaint.color = teal
                    canvas.drawRect(MARGIN, 30f, PAGE_WIDTH - MARGIN, 33f, fillPaint)
                    canvas.drawText("FACTURA CLARA  /  COPIA DIGITAL", MARGIN, 23f, detailPaint)
                    val review = plan.sourceIndex in reviewSources
                    val marked = plan.sourceIndex in highlighted
                    val sourceLabel = when {
                        marked -> "Origen ${plan.sourceIndex}  ·  REVISAR LO RESALTADO"
                        review -> "Origen ${plan.sourceIndex}  ·  REVISAR"
                        else -> "Origen ${plan.sourceIndex}"
                    }
                    val labelPaint = if (review) cautionPaint else detailPaint
                    canvas.drawText(sourceLabel, PAGE_WIDTH - MARGIN - labelPaint.measureText(sourceLabel), 23f, labelPaint)
                    val sheet = plan.sheet
                    if (sheet != null) drawSheet(canvas, sheet, plan.sheetAspect, footerTop - 8f)
                    else plan.rows.forEach { drawRow(canvas, it) }
                    canvas.drawLine(MARGIN, footerTop, PAGE_WIDTH - MARGIN, footerTop, rulePaint)
                    footer.forEachIndexed { i, text -> canvas.drawText(text, MARGIN, footerTop + 14f + i * 11f, detailPaint) }
                    canvas.drawText(when {
                        designExample -> "Muestra de diseño con datos ficticios"
                        marked -> "Transcripción automática NO confirmada: el texto resaltado en amarillo puede contener errores"
                        review -> "Transcripción automática NO confirmada: compárala con el documento original"
                        else -> "Transcripción automática del documento original"
                    }, MARGIN, footerTop + 14f + footer.size * 11f, if (review) cautionPaint else detailPaint)
                    val count = "${index + 1} / ${plans.size}"
                    canvas.drawText(count, PAGE_WIDTH - MARGIN - detailPaint.measureText(count), footerTop + 14f, detailPaint)
                } finally { document.finishPage(page) }
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally { document.close() }
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 36f
        const val CONTENT_TOP = 48f
        const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
        /** A sheet fragment this many times taller than wide is sideways margin text. */
        private const val VERTICAL_ASPECT = 2.5f
    }
}

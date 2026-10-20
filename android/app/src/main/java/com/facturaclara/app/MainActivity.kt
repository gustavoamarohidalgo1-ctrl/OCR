package com.facturaclara.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar
import java.util.concurrent.Executors

/** Automatic transcription of local photo files. Text is selectable but never editable. */
class MainActivity : ComponentActivity() {
    private val ink by lazy { getColor(R.color.text_primary) }
    private val muted by lazy { getColor(R.color.text_secondary) }
    private val teal by lazy { getColor(R.color.accent) }
    private val onAccent by lazy { getColor(R.color.on_accent) }
    private val border by lazy { getColor(R.color.outline) }
    private val surface by lazy { getColor(R.color.background) }
    private val panel by lazy { getColor(R.color.panel) }
    private val positiveSurface by lazy { getColor(R.color.positive_surface) }
    private val selectedBorder by lazy { getColor(R.color.selected_outline) }
    private val cautionText by lazy { getColor(R.color.caution_text) }
    private val cautionSurface by lazy { getColor(R.color.caution_surface) }
    private val errorText by lazy { getColor(R.color.error_text) }
    private val errorSurface by lazy { getColor(R.color.error_surface) }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val repository by lazy { Repository(applicationContext) }
    private var resumed = false
    private var refreshing = false
    private var busy = false
    private var documents = emptyList<Invoice>()
    private var selectedId: String? = null
    private var pagePosition = 0
    private var detailsOpen = false
    private var pendingExportId: String? = null
    private var imageKey: String? = null
    private var textKey: String? = null
    private var detailsKey: String? = null
    private var listKey = ""
    private var selectedBitmap: Bitmap? = null
    private lateinit var message: TextView
    private lateinit var listCount: TextView
    private lateinit var documentList: LinearLayout
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var detailView: LinearLayout
    private lateinit var emptyPane: ScrollView
    private lateinit var detailPane: ScrollView
    private lateinit var fileName: TextView
    private lateinit var fileInfo: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var detailsToggle: TextView
    private lateinit var detailsList: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var originalImage: ImageView
    private lateinit var imageMessage: TextView
    private lateinit var transcription: TextView
    private lateinit var addButton: Button
    private lateinit var emptyAddButton: Button
    private lateinit var exportButton: Button
    private lateinit var uploadAgainButton: Button
    private lateinit var rereadButton: Button

    private val openDocuments = registerForActivityResult(object : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }) { uris ->
        if (uris.isNotEmpty()) importDocuments(uris)
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { output ->
        val id = pendingExportId
        pendingExportId = null
        if (output != null && id != null) exportPdf(id, output)
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!resumed || isDestroyed) return
            refresh()
            main.postDelayed(this, 1500)
        }
    }
    private val hideMessage = Runnable { message.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lightBars = resources.getBoolean(R.bool.light_system_bars)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        selectedId = savedInstanceState?.getString("selected")
        pagePosition = savedInstanceState?.getInt("page") ?: 0
        detailsOpen = savedInstanceState?.getBoolean("details") ?: false
        pendingExportId = savedInstanceState?.getString("export_id")
        buildInterface()
    }
    override fun onResume() { super.onResume(); resumed = true; main.removeCallbacks(poll); main.post(poll) }
    override fun onPause() { resumed = false; main.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { main.removeCallbacks(poll); main.removeCallbacks(hideMessage); executor.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId)
        outState.putInt("page", pagePosition)
        outState.putBoolean("details", detailsOpen)
        outState.putString("export_id", pendingExportId)
        super.onSaveInstanceState(outState)
    }

    private fun buildInterface() {
        val tablet = resources.configuration.screenWidthDp >= 800
        val wide = resources.configuration.screenWidthDp >= 1000
        val side = dp(if (tablet) 20 else 12)
        val root = column().apply { setBackgroundColor(surface) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // One header line: the app name, the offline badge and the only way to add documents.
        val header = row().apply { setPadding(side + dp(4), dp(12), side, dp(12)); setBackgroundColor(panel); gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("▤", 26f, teal).apply { setPadding(0, 0, dp(10), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        header.addView(label("Factura Clara", 20f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (tablet) header.addView(label("●  Sin internet", 11f, teal, true).apply {
            setPadding(dp(11), dp(7), dp(11), dp(7)); background = rounded(positiveSurface, 16)
            contentDescription = "Funciona sin internet. Tus archivos se quedan en este dispositivo."
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        addButton = action(if (tablet) "＋  Agregar boleta" else "＋  Agregar", true) { launchFilePicker() }.apply { contentDescription = "Agregar boleta: elige una foto o un PDF" }
        header.addView(addButton)
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        message = label("", 13f, teal).apply {
            visibility = View.GONE; setPadding(side + dp(4), dp(10), side, dp(10)); setBackgroundColor(positiveSurface)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (text.isNotBlank()) AlertDialog.Builder(this@MainActivity).setMessage(text)
                    .setPositiveButton("Cerrar") { _, _ -> visibility = View.GONE }.show()
            }
        }
        root.addView(message)
        val body = LinearLayout(this).apply {
            orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(side, dp(16), side, dp(14))
        }
        val queue = column().apply { setPadding(dp(10), dp(14), dp(10), dp(10)); background = rounded(panel, 12, border) }
        val queueHeader = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), dp(10)) }
        queueHeader.addView(label("Documentos", 15f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        listCount = label("", 12f, muted)
        queueHeader.addView(listCount)
        queue.addView(queueHeader)
        documentList = column()
        queue.addView(ScrollView(this).apply { addView(documentList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tablet) body.addView(queue, LinearLayout.LayoutParams(dp(300), -1).apply { marginEnd = dp(16) })
        else body.addView(queue, LinearLayout.LayoutParams(-1, dp(minOf(170, resources.configuration.screenHeightDp / 4))).apply { bottomMargin = dp(12) })
        val content = FrameLayout(this).apply { background = rounded(panel, 12, border) }
        emptyView = column().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        emptyView.addView(label("▤", 48f, teal).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        // Until the first load finishes the tray may still hold documents: say so instead of inviting a first upload.
        emptyTitle = label("Cargando tus documentos…", 22f, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }
        emptyView.addView(emptyTitle)
        emptyBody = label("Elige una foto o un PDF guardado en la tablet. La lectura empieza sola y el PDF aparece aquí, sin enviar nada a internet.", 14f, muted).apply { gravity = Gravity.CENTER; maxWidth = dp(400); visibility = View.GONE }
        // Wrap the width so maxWidth keeps the lines short; a match_parent TextView ignores it.
        emptyView.addView(emptyBody, LinearLayout.LayoutParams(-2, -2))
        emptyAddButton = action("＋  Agregar boleta", true) { launchFilePicker() }.apply { visibility = View.GONE }
        emptyView.addView(emptyAddButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        emptyPane = ScrollView(this).apply { isFillViewport = true; addView(emptyView, FrameLayout.LayoutParams(-1, -2)) }
        content.addView(emptyPane, FrameLayout.LayoutParams(-1, -1))
        detailView = column().apply { setPadding(dp(if (tablet) 24 else 15), dp(20), dp(if (tablet) 24 else 15), dp(20)) }
        fileName = label("", 22f, ink, true)
        fileInfo = label("", 12f, muted).apply { setPadding(0, dp(6), 0, dp(16)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        detailView.addView(fileName); detailView.addView(fileInfo)
        // A single status card: one plain sentence, the next action, and the reader's own notes folded away.
        statusCard = column()
        val statusRow = LinearLayout(this).apply {
            orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (tablet) Gravity.CENTER_VERTICAL else Gravity.START
        }
        val statusTexts = column()
        statusTitle = label("", 17f, ink, true)
        statusBody = label("", 14f, ink).apply { setPadding(0, dp(5), 0, 0) }
        statusTexts.addView(statusTitle); statusTexts.addView(statusBody)
        statusRow.addView(statusTexts, if (tablet) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        val statusActions = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(if (tablet) dp(16) else 0, if (tablet) 0 else dp(12), 0, 0) }
        exportButton = action("Guardar PDF", true) { prepareExport() }
        // Documents blocked by an older version keep their original: read them again
        // so they also get a PDF for review instead of requiring a new photo.
        rereadButton = action("Volver a leer", true) { reread() }
        uploadAgainButton = action("Subir otra foto") { launchFilePicker() }
        statusActions.addView(exportButton); statusActions.addView(rereadButton); statusActions.addView(uploadAgainButton)
        statusRow.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(statusActions) })
        statusCard.addView(statusRow)
        detailsToggle = label("", 13f, teal, true).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(0, dp(8), dp(12), dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener { detailsOpen = !detailsOpen; renderDocument() }
        }
        statusCard.addView(detailsToggle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        detailsList = column().apply { setPadding(0, 0, 0, dp(10)) }
        statusCard.addView(detailsList)
        detailView.addView(statusCard)
        navigation = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0); visibility = View.GONE }
        previous = action("←  Anterior") { changePage(-1) }.apply { contentDescription = "Página anterior" }
        next = action("Siguiente  →") { changePage(1) }.apply { contentDescription = "Página siguiente" }
        pageLabel = label("", 14f, ink, true).apply { gravity = Gravity.CENTER }
        navigation.addView(previous); navigation.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f)); navigation.addView(next)
        detailView.addView(navigation)
        val comparison = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL; setPadding(0, dp(20), 0, 0) }
        val source = column()
        source.addView(sectionTitle("Foto", "Toca para ampliar"))
        val imageFrame = FrameLayout(this).apply { background = rounded(surface, 9, border) }
        originalImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true; setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Foto original. Toca para ampliar."; isFocusable = true; setOnClickListener { showImageZoom() }
        }
        imageMessage = label("Preparando el original…", 13f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(20), dp(18), dp(20)) }
        imageFrame.addView(originalImage, FrameLayout.LayoutParams(-1, -1)); imageFrame.addView(imageMessage, FrameLayout.LayoutParams(-1, -1))
        source.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(if (wide) 430 else 300)))
        if (wide) comparison.addView(source, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        else comparison.addView(source, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        val textPanel = column()
        textPanel.addView(sectionTitle("Texto leído", "Puede contener errores"))
        transcription = label("El texto aparecerá cuando termine la lectura.", 13f, ink).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1f); setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        val textScroll = ScrollView(this).apply {
            background = rounded(panel, 9, border); addView(transcription)
            setOnTouchListener { view, event ->
                view.parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL); false
            }
        }
        textPanel.addView(textScroll, LinearLayout.LayoutParams(-1, dp(if (wide) 430 else 330)))
        if (wide) comparison.addView(textPanel, LinearLayout.LayoutParams(0, -2, 1f)) else comparison.addView(textPanel)
        detailView.addView(comparison)
        // Toggle the scroll containers, not their content: a visible ScrollView consumes touches even when empty.
        detailPane = ScrollView(this).apply { addView(detailView); visibility = View.GONE }
        content.addView(detailPane, FrameLayout.LayoutParams(-1, -1))
        if (tablet) body.addView(content, LinearLayout.LayoutParams(0, -1, 1f)) else body.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); ViewCompat.requestApplyInsets(root)
    }

    private fun reread() {
        val id = currentInvoice()?.id ?: return
        setBusy(true)
        executor.execute {
            try {
                val invoice = repository.get(id) ?: error("El documento ya no está disponible.")
                check(invoice.status == "needs_recapture") { "El documento ya se está procesando." }
                invoice.status = "queued"
                invoice.error = null
                repository.save(invoice)
                repository.enqueue(id)
                ui { setBusy(false); showMessage("Leyendo de nuevo el documento…"); refresh() }
            } catch (error: Exception) { ui { setBusy(false); showMessage("No se pudo volver a leer: ${friendly(error)}", true) } }
        }
    }

    private fun refresh() {
        if (refreshing || executor.isShutdown) return
        refreshing = true
        executor.execute {
            try {
                val loaded = repository.list().filterNot { it.isDesignExample }
                ui {
                    documents = loaded
                    if (selectedId == null || loaded.none { it.id == selectedId }) selectedId = loaded.firstOrNull()?.id
                    refreshing = false; renderList(); renderDocument()
                }
            } catch (error: Exception) { ui { refreshing = false; showMessage("No se pudo leer la bandeja: ${friendly(error)}", true) } }
        }
    }
    private fun renderList(force: Boolean = false) {
        listCount.text = if (documents.isEmpty()) "" else documents.size.toString()
        listCount.contentDescription = "${documents.size} documentos"
        val now = System.currentTimeMillis()
        // Relative dates ("Hoy", "Ayer") change at midnight and with the clock format.
        val dayKey = "${Calendar.getInstance().get(Calendar.DAY_OF_YEAR)}:${use24Hour()}"
        val signature = documents.joinToString { "${it.id}:${it.status}:${it.pages.size}:${it.error}" } + ":$selectedId:$dayKey"
        if (!force && listKey == signature) return
        listKey = signature; documentList.removeAllViews()
        if (documents.isEmpty()) {
            documentList.addView(label("Aún no hay documentos.", 13f, muted).apply { setPadding(dp(6), dp(10), dp(6), dp(20)) }); return
        }
        documents.forEach { invoice ->
            val selected = invoice.id == selectedId
            val (status, statusColor) = listStatus(invoice)
            val readable = DocumentLabels.readableName(invoice.name)
            val title = readable ?: fallbackTitle(invoice)
            // A fallback title already carries the date; the file name is what tells same-minute photos apart.
            val detail = if (readable == null) invoice.name else DocumentLabels.shortDate(invoice.createdAt, now, use24Hour())
            val card = column().apply {
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(if (selected) positiveSurface else panel, 8, if (selected) selectedBorder else border)
                isClickable = true; isFocusable = true
                contentDescription = "$title, $status, $detail${if (selected) ", seleccionado" else ""}"
                setOnClickListener {
                    if (selectedId != invoice.id) {
                        selectedId = invoice.id; pagePosition = 0; detailsOpen = false; textKey = null; renderList(true); renderDocument()
                    }
                }
            }
            card.addView(label(title, 14f, ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val meta = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
            meta.addView(label("●  $status", 12f, statusColor, true).apply { maxLines = 1 })
            meta.addView(label("  ·  $detail", 12f, muted).apply {
                maxLines = 1; ellipsize = if (readable == null) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(meta)
            documentList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }
    private fun renderDocument() {
        val invoice = currentInvoice()
        emptyPane.visibility = if (invoice == null) View.VISIBLE else View.GONE
        detailPane.visibility = if (invoice == null) View.GONE else View.VISIBLE
        if (invoice == null) {
            emptyTitle.text = "Agrega tu primera boleta"; emptyBody.visibility = View.VISIBLE; emptyAddButton.visibility = View.VISIBLE
            updateControls(); return
        }
        fileName.text = title(invoice)
        val pageCount = when (invoice.pages.size) { 0 -> null; 1 -> "1 página"; else -> "${invoice.pages.size} páginas" }
        fileInfo.text = listOfNotNull(DocumentLabels.shortDate(invoice.createdAt, System.currentTimeMillis(), use24Hour()), pageCount, invoice.name)
            .joinToString("  ·  ")
        pagePosition = pagePosition.coerceIn(0, (invoice.pages.size - 1).coerceAtLeast(0))
        val page = currentPage()
        val nextTextKey = "${invoice.id}:${page?.index}:${page?.text?.hashCode()}:${invoice.status}"
        if (textKey != nextTextKey) { transcription.text = page?.text ?: if (isProcessing(invoice)) "Leyendo el documento…" else "No hay una transcripción disponible."; textKey = nextTextKey }
        navigation.visibility = if (invoice.pages.size > 1) View.VISIBLE else View.GONE
        pageLabel.text = "Página ${pagePosition + 1} de ${invoice.pages.size}"
        previous.isEnabled = pagePosition > 0; next.isEnabled = pagePosition + 1 < invoice.pages.size
        previous.alpha = if (previous.isEnabled) 1f else .45f; next.alpha = if (next.isEnabled) 1f else .45f
        loadOriginal(page)
        renderStatus(invoice, page)
        updateControls()
    }

    /** One summary for the document; the pipeline's own messages stay verbatim under "Ver detalles". */
    private fun renderStatus(invoice: Invoice, page: ScanPage?) {
        val pdf = invoice.mime == "application/pdf"
        val canReread = canReread(invoice)
        val (title, summary) = when (invoice.status) {
            "queued" -> "En espera" to "Se leerá después de los documentos anteriores. El PDF aparecerá aquí automáticamente."
            "processing" -> "Leyendo la boleta…" to "El PDF aparecerá aquí automáticamente. Puedes seguir agregando boletas mientras tanto."
            "ready" -> "✓  PDF listo" to "Su texto se puede seleccionar y buscar. Toca Guardar PDF para elegir dónde guardarlo."
            "review" -> "Revisa este PDF antes de usarlo" to
                "Algunas partes no se leyeron con seguridad. Compáralas con la foto: en el PDF aparecen resaltadas en amarillo cuando se pueden ubicar."
            "needs_recapture" -> (if (pdf) "Necesitamos un archivo más legible" else "Necesitamos una foto más clara") to when {
                canReread -> "No se generó el PDF. Toca Volver a leer para intentarlo de nuevo con este mismo archivo, o sube uno más claro."
                pdf -> "No se generó el PDF. Importa otro archivo más legible."
                else -> "No se generó el PDF. Toma otra foto con la boleta completa, enfocada y sin reflejos."
            }
            "failed" -> "No se pudo procesar" to (invoice.error?.takeIf { it.isNotBlank() } ?: "No se generó el PDF. Prueba con otra foto o archivo.")
            else -> "Pendiente" to "El documento se procesa automáticamente en esta tablet."
        }
        val (cardSurface, accent, stroke) = when (invoice.status) {
            "ready" -> Triple(positiveSurface, teal, null)
            "review" -> Triple(cautionSurface, cautionText, null)
            "needs_recapture", "failed" -> Triple(errorSurface, errorText, null)
            else -> Triple(surface, ink, border)
        }
        statusCard.background = rounded(cardSurface, 10, stroke)
        statusTitle.text = title; statusTitle.setTextColor(accent)
        statusBody.text = summary
        // A failed document already shows its error as the summary; any other error is about the whole document.
        val documentError = invoice.error?.takeIf { it.isNotBlank() && invoice.status != "failed" }
        val blockers = page?.blockers.orEmpty()
        val notes = page?.warnings.orEmpty()
        val count = (if (documentError == null) 0 else 1) + blockers.size + notes.size
        val onPage = if (invoice.pages.size > 1) " · página ${pagePosition + 1}" else ""
        detailsToggle.visibility = if (count == 0) View.GONE else View.VISIBLE
        detailsToggle.setTextColor(accent)
        detailsToggle.text = if (detailsOpen) "Ocultar detalles  ▴" else "Ver detalles ($count)  ▾"
        detailsToggle.contentDescription = if (detailsOpen) "Ocultar detalles" else "Ver $count detalles"
        statusCard.setPadding(dp(18), dp(16), dp(18), if (count == 0) dp(16) else dp(4))
        val key = "${invoice.id}:${page?.index}:$documentError:$blockers:$notes:$detailsOpen:$accent"
        if (key == detailsKey) return
        detailsKey = key; detailsList.removeAllViews()
        detailsList.visibility = if (detailsOpen && count > 0) View.VISIBLE else View.GONE
        if (!detailsOpen) return
        if (documentError != null) {
            detailsList.addView(detailHeading("Documento", accent))
            detailsList.addView(detailItem(documentError, true))
        }
        if (blockers.isNotEmpty()) {
            detailsList.addView(detailHeading("Avisos$onPage", accent))
            blockers.forEach { detailsList.addView(detailItem(it, true)) }
        }
        if (notes.isNotEmpty()) {
            detailsList.addView(detailHeading("Notas de la lectura$onPage", accent))
            notes.forEach { detailsList.addView(detailItem(it, false)) }
        }
    }
    private fun updateControls() {
        listOf(addButton, emptyAddButton).forEach { it.isEnabled = !busy; it.alpha = if (busy) .5f else 1f }
        val invoice = currentInvoice() ?: return
        exportButton.visibility = if (hasPdf(invoice) && invoice.pdfPath != null) View.VISIBLE else View.GONE
        val canReread = canReread(invoice)
        rereadButton.visibility = if (canReread) View.VISIBLE else View.GONE
        uploadAgainButton.visibility = if (invoice.status == "needs_recapture" || invoice.status == "failed") View.VISIBLE else View.GONE
        uploadAgainButton.text = if (invoice.mime == "application/pdf") "Importar otro PDF" else "Subir otra foto"
        // Only one filled button at a time: "Volver a leer" leads when it is offered.
        style(uploadAgainButton, primary = !canReread)
        (uploadAgainButton.layoutParams as LinearLayout.LayoutParams).let { params ->
            val margin = if (canReread) dp(9) else 0
            if (params.marginStart != margin) { params.marginStart = margin; uploadAgainButton.layoutParams = params }
        }
        listOf(exportButton, rereadButton, uploadAgainButton).forEach { it.isEnabled = !busy; it.alpha = if (busy) .45f else 1f }
    }
    private fun canReread(invoice: Invoice) =
        invoice.status == "needs_recapture" && invoice.pages.isNotEmpty() && invoice.pages.all { it.text.isNotBlank() }
    private fun currentInvoice() = documents.find { it.id == selectedId }
    private fun currentPage() = currentInvoice()?.pages?.getOrNull(pagePosition)
    private fun isProcessing(invoice: Invoice) = invoice.status == "queued" || invoice.status == "processing"
    private fun hasPdf(invoice: Invoice) = invoice.status == "ready" || invoice.status == "review"
    private fun title(invoice: Invoice) = DocumentLabels.readableName(invoice.name) ?: fallbackTitle(invoice)
    private fun fallbackTitle(invoice: Invoice) =
        DocumentLabels.fallbackTitle(invoice.mime == "application/pdf", invoice.createdAt, use24Hour())
    private fun use24Hour() = DateFormat.is24HourFormat(this)
    private fun listStatus(invoice: Invoice): Pair<String, Int> = when (invoice.status) {
        "queued" -> "En espera" to muted
        "processing" -> "Leyendo…" to muted
        "ready" -> "Listo" to teal
        "review" -> "Revisar" to cautionText
        "needs_recapture" -> (if (invoice.mime == "application/pdf") "Necesita otro archivo" else "Necesita otra foto") to errorText
        "failed" -> "No se pudo leer" to errorText
        else -> "Pendiente" to muted
    }
    private fun changePage(delta: Int) {
        val invoice = currentInvoice() ?: return
        pagePosition = (pagePosition + delta).coerceIn(0, (invoice.pages.size - 1).coerceAtLeast(0)); textKey = null; renderDocument()
    }
    private fun launchFilePicker() {
        try { openDocuments.launch(arrayOf("image/*", "application/pdf")) }
        catch (_: ActivityNotFoundException) { showMessage("Este dispositivo no tiene un selector de archivos disponible.", true) }
    }
    private fun importDocuments(uris: List<Uri>) {
        setBusy(true); showMessage("Agregando ${uris.size} documentos al lote…")
        executor.execute {
            val added = mutableListOf<Invoice>(); val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                try {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                    added.add(repository.importUri(uri))
                } catch (error: Exception) { failures.add("Archivo ${index + 1}: ${friendly(error)}") }
                ui { showMessage("Procesados ${index + 1} de ${uris.size} archivos para importar.") }
            }
            ui {
                setBusy(false)
                added.firstOrNull()?.let { selectedId = it.id; pagePosition = 0; detailsOpen = false; textKey = null }
                refresh()
                if (failures.isEmpty()) showMessage("${added.size} documentos agregados. Puedes seguir cargando mientras se leen.")
                else showMessage("${added.size} agregados; ${failures.size} no se pudieron cargar. ${failures.joinToString(" · ")}", true)
            }
        }
    }
    private fun prepareExport() {
        val invoice = currentInvoice() ?: return
        if (!hasPdf(invoice) || invoice.pdfPath == null) return
        pendingExportId = invoice.id
        val name = invoice.name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(90)
        try { createPdf.launch("${name.ifBlank { "boleta" }}_digital.pdf") }
        catch (_: ActivityNotFoundException) { pendingExportId = null; showMessage("No hay un selector de destino para guardar el PDF.", true) }
    }
    private fun exportPdf(id: String, output: Uri) {
        setBusy(true); showMessage("Guardando el PDF digital…")
        executor.execute {
            try {
                val invoice = repository.get(id) ?: error("El documento ya no está disponible.")
                check(hasPdf(invoice)) { "El PDF todavía no está disponible." }
                PdfExporter(applicationContext).write(invoice, output)
                ui {
                    setBusy(false); showMessage("PDF digital guardado. El texto se puede seleccionar y buscar.")
                    if (resumed) AlertDialog.Builder(this).setTitle("Tu PDF está listo").setMessage("Se guardó en el destino que elegiste.")
                        .setNegativeButton("Cerrar", null).setPositiveButton("Abrir PDF") { _, _ ->
                            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(output, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: ActivityNotFoundException) { showMessage("El PDF se guardó. Ábrelo con un lector de PDF compatible.") }
                        }.show()
                }
            } catch (error: Exception) { ui { setBusy(false); showMessage("No se pudo guardar el PDF: ${friendly(error)}", true) } }
        }
    }
    private fun loadOriginal(page: ScanPage?) {
        val newKey = page?.imagePath
        if (imageKey == newKey) return
        imageKey = newKey; selectedBitmap = null; originalImage.setImageDrawable(null)
        imageMessage.visibility = View.VISIBLE; imageMessage.text = if (page == null) "Preparando el original…" else "Cargando imagen…"
        if (page == null) return
        executor.execute {
            val bitmap = try { decodeImage(page.imagePath, 1500) } catch (_: Exception) { null }
            ui { if (imageKey == newKey) { selectedBitmap = bitmap; originalImage.setImageBitmap(bitmap); imageMessage.visibility = if (bitmap == null) View.VISIBLE else View.GONE; imageMessage.text = "No se pudo abrir la imagen original." } }
        }
    }
    private fun showImageZoom() {
        val bitmap = selectedBitmap ?: return
        val zoom = ZoomImageView(this).apply { setImageBitmap(bitmap); contentDescription = "Original ampliable. Pellizca para acercar y arrastra para mover."; setBackgroundColor(surface) }
        val dialog = AlertDialog.Builder(this).setTitle("Original · Pellizca para ampliar").setView(zoom).setPositiveButton("Cerrar", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94).toInt(), (resources.displayMetrics.heightPixels * .88).toInt())
            zoom.layoutParams = zoom.layoutParams.apply { height = (resources.displayMetrics.heightPixels * .65).toInt() }
        }
        dialog.show()
    }
    private fun decodeImage(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private fun sectionTitle(title: String, hint: String) = row().apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(9))
        addView(label(title, 14f, ink, true))
        addView(label("  ·  $hint", 12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun detailHeading(text: String, color: Int) =
        label(text.uppercase(), 11f, color, true).apply { letterSpacing = .06f; setPadding(0, dp(10), 0, dp(4)) }
    private fun detailItem(text: String, doubt: Boolean) = row().apply {
        setPadding(0, dp(3), 0, dp(3))
        addView(label(if (doubt) "!" else "·", 13f, if (doubt) ink else muted, true).apply { gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(dp(18), -2))
        addView(label(text, 13f, if (doubt) ink else muted), LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun setBusy(value: Boolean) { busy = value; updateControls() }
    private fun showMessage(text: String, error: Boolean = false) {
        main.removeCallbacks(hideMessage)
        message.text = text; message.setTextColor(if (error) errorText else teal)
        message.setBackgroundColor(if (error) errorSurface else positiveSurface); message.visibility = View.VISIBLE
        // Confirmations clear themselves; errors stay until they are read.
        if (!error) main.postDelayed(hideMessage, 8000)
    }
    private fun friendly(error: Exception) = error.message?.take(250)?.takeIf { it.isNotBlank() } ?: "Ocurrió un error. Inténtalo otra vez."
    private fun ui(block: () -> Unit) { main.post { if (!isDestroyed && !isFinishing) block() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) } }
    private fun action(text: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 14f; isAllCaps = false; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48); minimumHeight = dp(48); minWidth = dp(48); stateListAnimator = null; elevation = 0f
        setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { click() }
        style(this, primary)
    }
    private fun style(button: Button, primary: Boolean) {
        button.setTextColor(if (primary) onAccent else teal)
        button.background = rounded(if (primary) teal else panel, 8, if (primary) teal else border)
    }
}

// This activity uses the platform Material theme, not an AppCompat theme or tinting.
@android.annotation.SuppressLint("AppCompatCustomView")
private class ZoomImageView(context: android.content.Context) : ImageView(context) {
    private val transform = Matrix()
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var moving = false
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (currentScale * detector.scaleFactor).coerceIn(1f, 6f)
            transform.postScale(next / currentScale, next / currentScale, detector.focusX, detector.focusY)
            currentScale = next; imageMatrix = transform; return true
        }
    })
    init { scaleType = ScaleType.MATRIX; isFocusable = true; isClickable = true }
    override fun setImageBitmap(bitmap: Bitmap?) { super.setImageBitmap(bitmap); post { fit() } }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    private fun fit() {
        val image = drawable ?: return
        if (width == 0 || height == 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        val scale = minOf(width.toFloat() / image.intrinsicWidth, height.toFloat() / image.intrinsicHeight)
        transform.setScale(scale, scale); transform.postTranslate((width - image.intrinsicWidth * scale) / 2f, (height - image.intrinsicHeight * scale) / 2f)
        currentScale = 1f; imageMatrix = transform
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; moving = false }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 3) moving = true
                    transform.postTranslate(dx, dy); imageMatrix = transform
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> if (!moving) performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

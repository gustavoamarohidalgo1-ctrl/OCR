package com.facturaclara.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.util.UUID

class Repository(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "documents").apply { mkdirs() }

    fun documentDir(id: String): File {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "Identificador de documento no válido." }
        return File(root, id).apply { mkdirs() }
    }

    fun get(id: String): Invoice? = synchronized(lock) {
        val file = AtomicFile(File(documentDir(id), "document.json"))
        val contents = try {
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return@synchronized null
        }
        fromJson(JSONObject(contents))
    }

    fun list(): List<Invoice> = synchronized(lock) {
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { directory ->
            try { get(directory.name) } catch (_: Exception) { null }
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    fun save(invoice: Invoice) = synchronized(lock) {
        val file = AtomicFile(File(documentDir(invoice.id), "document.json"))
        val stream = file.startWrite()
        try {
            stream.write(toJson(invoice).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun importUri(uri: Uri): Invoice {
        val resolver = app.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Factura"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
            }
        } catch (_: Exception) { /* File URIs and some providers do not support a query. */ }
        name = name.take(180)
        val mime = resolver.getType(uri) ?: if (name.endsWith(".pdf", true)) "application/pdf" else "image/*"
        require(mime == "application/pdf" || mime.startsWith("image/")) { "Importa una imagen o un PDF." }
        val id = UUID.randomUUID().toString()
        val directory = documentDir(id)
        val source = File(directory, if (mime == "application/pdf") "original.pdf" else "original.image")
        try {
            var total = 0L
            val input = resolver.openInputStream(uri) ?: throw IOException("No se puede abrir el archivo.")
            input.use { incoming ->
                source.outputStream().use { outgoing ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = incoming.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FILE_BYTES) { "El archivo supera 40 MB. Divide el PDF o usa una imagen más pequeña." }
                        outgoing.write(buffer, 0, count)
                    }
                }
            }
            require(total > 0) { "El archivo está vacío." }
            val invoice = Invoice(id, name, System.currentTimeMillis(), source.absolutePath, mime)
            save(invoice)
            enqueue(id)
            return invoice
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<InvoiceWorker>()
            .setInputData(workDataOf("id" to id)).addTag("invoice-$id").build()
        WorkManager.getInstance(app).enqueueUniqueWork("invoice-$id", ExistingWorkPolicy.KEEP, request)
    }

    private fun toJson(document: Invoice): JSONObject = JSONObject().apply {
        put("id", document.id); put("name", document.name); put("createdAt", document.createdAt)
        put("sourcePath", document.sourcePath); put("mime", document.mime); put("status", document.status)
        put("error", document.error ?: JSONObject.NULL); put("pdfPath", document.pdfPath ?: JSONObject.NULL)
        put("isDesignExample", document.isDesignExample)
        put("pages", JSONArray().apply {
            document.pages.forEach { page -> put(JSONObject().apply {
                put("index", page.index); put("imagePath", page.imagePath); put("text", page.text)
                put("warnings", JSONArray(page.warnings)); put("blockers", JSONArray(page.blockers))
                put("lines", JSONArray().apply {
                    page.lines.forEach { line -> put(JSONObject().apply {
                        put("text", line.text); put("left", line.left.toDouble()); put("top", line.top.toDouble())
                        put("right", line.right.toDouble()); put("bottom", line.bottom.toDouble())
                        put("slope", line.slope.toDouble())
                        put("confidence", line.confidence?.toDouble() ?: JSONObject.NULL)
                        if (line.doubtful) put("doubtful", true)
                    }) }
                })
            }) }
        })
    }

    private fun fromJson(json: JSONObject): Invoice = Invoice(
        id = json.getString("id"), name = json.getString("name"), createdAt = json.getLong("createdAt"),
        sourcePath = json.getString("sourcePath"), mime = json.getString("mime"), status = json.getString("status"),
        error = if (json.isNull("error")) null else json.getString("error"),
        pdfPath = if (json.isNull("pdfPath")) null else json.getString("pdfPath"),
        isDesignExample = json.optBoolean("isDesignExample", false),
        pages = json.optJSONArray("pages")?.let { pages -> (0 until pages.length()).map { index ->
            val page = pages.getJSONObject(index)
            ScanPage(page.getInt("index"), page.getString("imagePath"), page.getString("text"),
                strings(page.optJSONArray("warnings")), strings(page.optJSONArray("blockers")),
                ocrLines(page.optJSONArray("lines")))
        } } ?: emptyList()
    )

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }

    private fun ocrLines(array: JSONArray?): List<OcrLine> {
        if (array == null) return emptyList() // Documents saved before geometry was introduced.
        return try {
            (0 until array.length()).map { index ->
                val line = array.getJSONObject(index)
                OcrLine(line.getString("text"), line.getDouble("left").toFloat(), line.getDouble("top").toFloat(),
                    line.getDouble("right").toFloat(), line.getDouble("bottom").toFloat(),
                    line.optDouble("slope", 0.0).toFloat(),
                    if (line.isNull("confidence")) null else line.getDouble("confidence").toFloat(),
                    line.optBoolean("doubtful", false))
            }
        } catch (_: Exception) {
            // Retain the complete source.text when old or corrupt geometry cannot be used.
            emptyList()
        }
    }

    companion object {
        private val lock = Any()
        const val MAX_FILE_BYTES = 40L * 1024 * 1024
    }
}

package com.facturaclara.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Diagnostic only: tries candidate models that are not part of the app. Model files are
 * read from the app's external files dir under models/ (pushed by hand for the trial).
 * superResolution: Real-ESRGAN general x4v3 on whole small photos, then ML Kit on the result.
 * handwriting: TrOCR-small handwritten (encoder + decoder, greedy) on explicit line crops.
 */
@RunWith(AndroidJUnit4::class)
class ModelTrialProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val models = File(context.getExternalFilesDir(null), "models")
    private val environment = OrtEnvironment.getEnvironment()

    private fun output(name: String) = File(context.getExternalFilesDir(null), "model-trial/${arguments.getString("probeRun") ?: "trial"}")
        .apply { mkdirs() }.let { File(it, name) }

    private fun asset(name: String): Bitmap {
        val bytes = instrumentation.context.assets.open("real-receipt-photos/$name").use { it.readBytes() }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun session(file: String): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(4)
        if (arguments.getString("noOpt") != null) options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        environment.createSession(File(models, file).path, options)
    }

    private fun status(text: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n$text\n") })

    @Test fun superResolution() {
        val photos = arguments.getString("srPhotos")?.split(',')?.filter { it.isNotBlank() }
        assumeTrue("Pass -e srPhotos", !photos.isNullOrEmpty())
        val results = JSONArray()
        session("realesr-general-x4v3.onnx").use { sr ->
            status("SR inputs ${sr.inputInfo.mapValues { it.value.info }} outputs ${sr.outputInfo.mapValues { it.value.info }}")
            OfflineOcr().use { ml ->
                for (photo in photos!!) {
                    val source = asset(photo)
                    val started = System.currentTimeMillis()
                    val enlarged = upscale(sr, source)
                    val elapsed = System.currentTimeMillis() - started
                    FileOutputStream(output(photo.substringBeforeLast('.') + "-x4.png")).use { enlarged.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val base = ml.recognize(source)
                    val improved = ml.recognize(enlarged)
                    results.put(JSONObject().put("photo", photo).put("sr_ms", elapsed)
                        .put("width", enlarged.width).put("height", enlarged.height)
                        .put("baseline_text", base.text).put("sr_text", improved.text))
                    status("$photo SR ${enlarged.width}x${enlarged.height} in $elapsed ms\n--- base:\n${base.text}\n--- SR:\n${improved.text}")
                }
            }
        }
        output("superres.json").writeText(results.toString(1))
    }

    /** One pass of the x4 model over the whole bitmap (meant for small photos only). */
    private fun upscale(sr: OrtSession, source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val input = FloatArray(3 * w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = ((p shr 16) and 255) / 255f
            input[w * h + i] = ((p shr 8) and 255) / 255f
            input[2 * w * h + i] = (p and 255) / 255f
        }
        val name = sr.inputNames.first()
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            sr.run(mapOf(name to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val oh = shape[2].toInt()
                val ow = shape[3].toInt()
                val values = out.floatBuffer
                val plane = ow * oh
                val outPixels = IntArray(plane)
                for (i in 0 until plane) {
                    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + .5f).toInt()
                    outPixels[i] = (255 shl 24) or (c(values.get(i)) shl 16) or (c(values.get(plane + i)) shl 8) or c(values.get(2 * plane + i))
                }
                return Bitmap.createBitmap(outPixels, ow, oh, Bitmap.Config.ARGB_8888)
            }
        }
    }

    @Test fun handwriting() {
        val photo = arguments.getString("hwPhoto")
        val spec = arguments.getString("hwCrops")
        assumeTrue("Pass -e hwPhoto and -e hwCrops", photo != null && spec != null)
        val vocab = JSONObject(File(models, "tokenizer.json").readText()).getJSONObject("model").getJSONArray("vocab")
            .let { array -> List(array.length()) { array.getJSONArray(it).getString(0) } }
        val bitmap = if (photo == "synthetic") synthetic() else asset(photo!!)
        val results = JSONArray()
        session("encoder_model_quantized.onnx").use { encoder ->
            session("decoder_model_quantized.onnx").use { decoder ->
                status("encoder in ${encoder.inputNames} out ${encoder.outputNames}; decoder in ${decoder.inputNames} out ${decoder.outputNames}")
                for (item in spec!!.split(';').filter { it.isNotBlank() }) {
                    val (name, values) = item.split(':', limit = 2)
                    val v = values.split(',').map { it.trim().toFloat() }
                    val left = (v[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val top = (v[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val right = (v[2] * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = (v[3] * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    FileOutputStream(output("crop-$name.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val started = System.currentTimeMillis()
                    val (text, confidence) = readLine(encoder, decoder, vocab, crop)
                    results.put(JSONObject().put("name", name).put("text", text).put("confidence", confidence.toDouble())
                        .put("ms", System.currentTimeMillis() - started))
                    status("$name: '$text' (conf ${"%.2f".format(confidence)}, ${System.currentTimeMillis() - started} ms) ids=${lastIds.take(14)} shifted='${decodeShifted(lastIds, vocab)}'")
                }
            }
        }
        output("handwriting-${photo!!.substringBeforeLast('.')}.json").writeText(results.toString(1))
    }

    /** Black printed words on white, as a control that any working text model reads. */
    private fun synthetic(): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = 64f; isAntiAlias = true }
        canvas.drawText("HELLO WORLD", 40f, 80f, paint)
        canvas.drawText("thank you for visiting", 40f, 170f, paint)
        return bitmap
    }

    /** Gray, contrast stretched between the 2nd and 98th percentile, with a white margin. */
    private fun prepared(crop: Bitmap): Bitmap {
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h).also { crop.getPixels(it, 0, w, 0, 0, w, h) }
        val gray = IntArray(pixels.size) { val p = pixels[it]; (299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000 }
        val sorted = gray.sorted()
        val low = sorted[(sorted.size * .02).toInt()]
        val high = max(low + 1, sorted[(sorted.size * .98).toInt().coerceAtMost(sorted.size - 1)])
        val margin = max(4, h / 4)
        val out = Bitmap.createBitmap(w + 2 * margin, h + 2 * margin, Bitmap.Config.ARGB_8888)
        out.eraseColor(android.graphics.Color.WHITE)
        val stretched = IntArray(pixels.size) { val v = ((gray[it] - low) * 255 / (high - low)).coerceIn(0, 255); (255 shl 24) or (v shl 16) or (v shl 8) or v }
        out.setPixels(stretched, 0, w, margin, margin, w, h)
        return out
    }

    private fun readLine(encoder: OrtSession, decoder: OrtSession, vocab: List<String>, raw: Bitmap): Pair<String, Float> {
        val size = 384
        val crop = if (arguments.getString("prep") != null) prepared(raw) else raw
        val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
        val pixels = IntArray(size * size).also { scaled.getPixels(it, 0, size, 0, 0, size, size) }
        val input = FloatArray(3 * size * size)
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = (((p shr 16) and 255) / 255f - .5f) / .5f
            input[size * size + i] = (((p shr 8) and 255) / 255f - .5f) / .5f
            input[2 * size * size + i] = ((p and 255) / 255f - .5f) / .5f
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { pixelValues ->
            encoder.run(mapOf(encoder.inputNames.first() to pixelValues)).use { encoded ->
                val hidden = encoded[0] as OnnxTensor
                val ids = mutableListOf(2L)
                var logProbability = 0.0
                repeat(48) {
                    val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong()))
                    idsTensor.use {
                        val feeds = decoder.inputNames.associateWith { name -> if (name.contains("input_ids")) idsTensor else hidden }
                        decoder.run(feeds).use { out ->
                            val logits = out[0] as OnnxTensor
                            val shape = logits.info.shape
                            val classes = shape[2].toInt()
                            val buffer = logits.floatBuffer
                            val offset = (shape[1].toInt() - 1) * classes
                            var best = 0
                            var bestValue = Float.NEGATIVE_INFINITY
                            for (k in 0 until classes) { val value = buffer.get(offset + k); if (value > bestValue) { bestValue = value; best = k } }
                            var sum = 0.0
                            for (k in 0 until classes) sum += exp((buffer.get(offset + k) - bestValue).toDouble())
                            logProbability += -kotlin.math.ln(sum)
                            if (best == 2) { lastIds = ids.toList(); return decode(ids, vocab) to exp(logProbability / max(1, ids.size)).toFloat() }
                            ids += best.toLong()
                        }
                    }
                }
                lastIds = ids.toList()
                return decode(ids, vocab) to exp(logProbability / max(1, ids.size)).toFloat()
            }
        }
    }

    private var lastIds: List<Long> = emptyList()

    private fun decodeShifted(ids: List<Long>, vocab: List<String>) =
        ids.drop(1).mapNotNull { vocab.getOrNull(it.toInt() - 1) }.joinToString("").replace('▁', ' ').trim()

    private fun decode(ids: List<Long>, vocab: List<String>) =
        ids.drop(1).mapNotNull { vocab.getOrNull(it.toInt()) }.filterNot { it in setOf("<s>", "</s>", "<pad>", "<unk>") }
            .joinToString("").replace('▁', ' ').trim()
}

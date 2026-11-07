package com.example.foodify.local

import android.app.Activity
import android.graphics.ImageDecoder
import ai.onnxruntime.*
import com.google.gson.JsonParser
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

/** Food-101 candidates only; multiple dishes and unknown classes are reviewed downstream. */
class FoodClassifierBridge(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "foodify/food_classifier")
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var disposed = false
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private var labels: List<String> = emptyList()
    init {
        channel.setMethodCallHandler { call, result ->
            if (call.method != "classify") { result.notImplemented(); return@setMethodCallHandler }
            if (android.os.Build.VERSION.SDK_INT < 28) { result.error("UNSUPPORTED", "기기 음식 인식을 지원하지 않는 Android 버전입니다.", null); return@setMethodCallHandler }
            val paths = call.argument<List<String>>("paths")
            if (paths == null || paths.size !in 1..3) { result.error("INVALID_IMAGES", "사진을 확인해 주세요.", null); return@setMethodCallHandler }
            if (!busy.compareAndSet(false, true)) { result.error("BUSY", "음식을 분석하고 있습니다.", null); return@setMethodCallHandler }
            worker.execute {
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    initialize()
                    val predictions = paths.mapIndexed { index, path -> classify(index, path) }
                    val answer = mapOf("model" to "Food-101-93M", "revision" to "addcbf20006b2ec0eb1bdff313ef732cd8634f11",
                        "predictions" to predictions, "elapsedMs" to android.os.SystemClock.elapsedRealtime() - started)
                    activity.runOnUiThread { if (!disposed) result.success(answer) }
                } catch (error: Exception) {
                    activity.runOnUiThread { if (!disposed) result.error("CLASSIFIER_FAILED", "기기 음식 인식을 완료하지 못했습니다.", null) }
                } catch (error: LinkageError) {
                    activity.runOnUiThread { if (!disposed) result.error("CLASSIFIER_UNAVAILABLE", "기기 음식 인식을 사용할 수 없습니다.", null) }
                } finally { busy.set(false) }
            }
        }
    }
    private fun initialize() {
        if (session != null) return
        check(!disposed)
        val metadata = activity.assets.open("food101/manifest.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val digest = metadata["sha256"].asString
        val dir = File(activity.noBackupFilesDir, "food101").apply { mkdirs() }
        val model = File(dir, "$digest.onnx")
        if (!model.exists() || model.length() != metadata["bytes"].asLong) {
            val partial = File(dir, "model.partial")
            try {
                activity.assets.open("food101/food101.onnx").use { source -> partial.outputStream().use { source.copyTo(it) } }
                val hash = MessageDigest.getInstance("SHA-256")
                partial.inputStream().use { stream -> val buffer = ByteArray(1048576); while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) } }
                check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == digest)
                java.nio.file.Files.move(partial.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { partial.delete() }
        }
        labels = activity.assets.open("food101/labels.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray.map { entry -> entry.asString } }
        check(labels.size == 101)
        val options = OrtSession.SessionOptions()
        options.use { it.setIntraOpNumThreads(4); session = env.createSession(model.path, it) }
    }
    private fun classify(index: Int, path: String): Map<String, Any> {
        check(!disposed)
        val file = File(path).canonicalFile
        require(listOf(activity.cacheDir, activity.filesDir).any { file.toPath().startsWith(it.canonicalFile.toPath()) } && file.length() in 1..20971520L)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(224, 224)
        }
        val floats = FloatArray(3 * 224 * 224)
        try {
            val pixels = IntArray(224 * 224); bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            for (i in pixels.indices) {
                val color = pixels[i]
                floats[i] = ((color shr 16) and 255) / 127.5f - 1f
                floats[i + 224*224] = ((color shr 8) and 255) / 127.5f - 1f
                floats[i + 2*224*224] = (color and 255) / 127.5f - 1f
            }
        } finally { bitmap.recycle() }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), longArrayOf(1, 3, 224, 224)).use { tensor ->
            session!!.run(mapOf("pixel_values" to tensor)).use { output ->
                @Suppress("UNCHECKED_CAST")
                val logits = (output[0].value as Array<FloatArray>)[0]
                check(logits.size == 101 && logits.all { it.isFinite() })
                val maximum = logits.max(); val weights = logits.map { exp((it - maximum).toDouble()) }; val total = weights.sum()
                val top = logits.indices.sortedByDescending { logits[it] }.take(3)
                return mapOf("imageIndex" to index, "candidates" to top.map { mapOf("label" to labels[it], "score" to weights[it] / total) })
            }
        }
    }
    fun evaluateBatch() {
        if (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0 || !busy.compareAndSet(false, true)) return
        worker.execute {
            val directory = File(activity.filesDir, "evaluation50")
            val output = File(directory, "classifier-results.json")
            val records = mutableListOf<Map<String, Any>>()
            try {
                initialize()
                val entries = JsonParser.parseString(File(directory, "manifest.json").readText()).asJsonArray
                require(entries.size() in 1..50)
                for (entry in entries) {
                    val name = entry.asJsonObject["file"].asString
                    require(Regex("foodify-fifty-[0-9]{2}\\.jpg").matches(name))
                    val start = android.os.SystemClock.elapsedRealtime()
                    val prediction = classify(0, File(directory, name).path)
                    records.add(mapOf("file" to name, "prediction" to prediction, "elapsedMs" to android.os.SystemClock.elapsedRealtime()-start))
                    output.writeText(com.google.gson.Gson().toJson(mapOf("records" to records, "complete" to false)))
                }
                output.writeText(com.google.gson.Gson().toJson(mapOf("records" to records, "complete" to true)))
            } catch (e: Exception) { output.writeText(com.google.gson.Gson().toJson(mapOf("records" to records, "error" to (e.message ?: "Evaluation failed"), "complete" to false))) }
            finally { busy.set(false) }
        }
    }
    fun dispose() {
        if (disposed) return
        disposed = true; channel.setMethodCallHandler(null)
        worker.execute { session?.close(); session = null }; worker.shutdown()
    }
}

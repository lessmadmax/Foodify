package com.example.foodify.local

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Developer-only local inference. Model and images stay in the app's private storage. */
class LocalModelBridge(private val activity: Activity, messenger: BinaryMessenger) {
    private val channel = MethodChannel(messenger, "foodify/local_model")
    private val worker = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val conversationLock = Any()
    private var activeConversation: Conversation? = null
    // Engine ownership and cleanup are confined to the serial worker.
    private val engines = ReusableResource<Engine> { it.close() }
    @Volatile private var modelLoaded = false
    private val busy = AtomicBoolean(false)
    @Volatile private var disposed = false
    @Volatile private var stage = "대기"
    @Volatile private var copied = 0L
    private var pickerResult: MethodChannel.Result? = null
    private val folder = File(activity.noBackupFilesDir, "local-models").apply { mkdirs() }
    private val model = File(folder, "gemma-4-E2B-it.litertlm")
    private val receipt = File(folder, "verified.sha256")
    private val size = 2588147712L
    private val digest = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "status" -> result.success(status())
                "importModel" -> {
                    if (!busy.compareAndSet(false, true)) result.error("BUSY", "작업이 진행 중입니다.", null)
                    else {
                        stage = "모델 파일 선택"; copied = 0; pickerResult = result
                        try {
                            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                            }, 7402)
                        } catch (e: Exception) {
                            pickerResult = null; busy.set(false)
                            result.error("IMPORT_FAILED", e.message, null)
                        }
                    }
                }
                "releaseModel" -> run(result) { releaseEngine(); status() }
                "analyze" -> {
                    val path = call.argument<String>("path")
                    if (path == null) result.error("IMAGE_REQUIRED", "사진을 선택해 주세요.", null)
                    else run(result) { analyze(path) }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun ready() = model.length() == size && receipt.exists() &&
        receipt.readText() == "$digest:$size:${model.lastModified()}"

    private fun status() = mapOf("ready" to ready(), "busy" to busy.get(), "stage" to stage,
        "copiedBytes" to copied, "totalBytes" to size, "modelLoaded" to modelLoaded)

    fun onFileResult(code: Int, intent: Intent?) {
        val result = pickerResult ?: return
        pickerResult = null
        val uri = intent?.data
        if (code != Activity.RESULT_OK || uri == null) {
            busy.set(false); stage = "대기"; result.success(null); return
        }
        // The busy flag remains held across the picker and streaming copy.
        worker.execute {
            val partial = File(folder, "model.partial")
            try {
                check(!partial.exists() || partial.delete()) { "이전 임시 모델 파일을 정리할 수 없습니다." }
                check(folder.usableSpace > size + 268435456L) { "앱 저장 공간을 3GB 이상 확보해 주세요." }
                stage = "모델 복사 및 SHA256 검증"
                val hash = MessageDigest.getInstance("SHA-256")
                activity.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "선택한 파일을 열 수 없습니다." }
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            check(!disposed) { "작업이 종료되었습니다." }
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= size) { "지정된 Gemma 모델과 파일 크기가 다릅니다." }
                            hash.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                }
                val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(copied == size && actual == digest) { "모델 무결성 검증에 실패했습니다. 지정된 모델 파일을 선택해 주세요." }
                releaseEngine()
                java.nio.file.Files.move(partial.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                receipt.writeText("$digest:$size:${model.lastModified()}")
                finish(result, status())
            } catch (e: Exception) { fail(result, e) }
            finally { partial.delete() }
        }
    }

    private fun run(result: MethodChannel.Result, action: () -> Any) {
        if (!busy.compareAndSet(false, true)) { result.error("BUSY", "작업이 진행 중입니다.", null); return }
        worker.execute {
            try { finish(result, action()) }
            catch (e: Exception) { releaseEngine(); fail(result, e) }
            catch (e: LinkageError) { releaseEngine(); fail(result, IllegalStateException("이 기기에서 실행 라이브러리를 불러올 수 없습니다.", e)) }
        }
    }

    private fun analyze(path: String): Map<String, Any> {
        check(Build.VERSION.SDK_INT >= 28) { "기기 내 분석은 Android 9 이상에서 지원합니다." }
        check(ready()) { "검증된 모델을 먼저 불러와 주세요." }
        val image = File(path).canonicalFile
        val roots = listOf(activity.cacheDir, activity.filesDir).map { it.canonicalFile.toPath() }
        require(roots.any { image.toPath().startsWith(it) } && image.length() in 1..20971520L) { "앱에서 선택한 사진을 사용해 주세요." }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(image)) { decoder, info, _ ->
            val ratio = minOf(1.0, 512.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = try { ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); output.toByteArray()
        } } finally { bitmap.recycle() }
        check(!disposed)
        val reused = engines.isLoaded
        stage = if (reused) "준비된 모델 사용" else "모델 초기화"
        val started = SystemClock.elapsedRealtime()
        val engine = engines.acquire {
            val cache = File(activity.cacheDir, "gemma4").apply { mkdirs() }
            val candidate = Engine(EngineConfig(model.absolutePath, backend = Backend.GPU(),
                visionBackend = Backend.GPU(), maxNumTokens = 4096, maxNumImages = 1, cacheDir = cache.path))
            try {
                candidate.initialize()
                check(!disposed)
                candidate
            } catch (error: Throwable) {
                runCatching { candidate.close() }
                throw error
            }
        }
        modelLoaded = true
        // Only the engine is reused. Each image gets an isolated conversation.
        run {
            val initialized = SystemClock.elapsedRealtime()
            check(!disposed)
            stage = "사진 분석"
            engine.createConversation(ConversationConfig(
                samplerConfig = SamplerConfig(1, 1.0, 0.0, 42),
                maxOutputToken = 256, thinkingConfig = ThinkingConfig(false),
                automaticToolCalling = false
            )).use { conversation ->
                synchronized(conversationLock) { activeConversation = conversation }
                val timedOut = AtomicBoolean(false)
                val timeout = watchdog.schedule({
                    synchronized(conversationLock) {
                        if (activeConversation === conversation) {
                            timedOut.set(true)
                            conversation.cancelProcess()
                        }
                    }
                }, 180, TimeUnit.SECONDS)
                val prompt = """사진에 보이는 음식의 이름을 한국어로 식별하세요. 접시, 그릇, 식탁, 배경은 제외하세요.
각각 구별되는 음식을 하나씩 나열하세요. 추측할 수 없는 음식은 '알 수 없는 음식'으로 표시하세요.
설명이나 코드 블록 없이 다음 형식의 JSON만 출력하세요: {"foods":[{"name":"음식명"}]}"""
                val response = try {
                    conversation.sendMessage(Contents.of(Content.ImageBytes(bytes), Content.Text(prompt)))
                } finally {
                    timeout.cancel(false)
                    synchronized(conversationLock) { activeConversation = null }
                }
                check(!timedOut.get()) { "분석이 3분을 초과했습니다. 앱을 다시 열고 더 단순한 사진으로 시도해 주세요." }
                check(!disposed)
                val ended = SystemClock.elapsedRealtime()
                return mapOf("foods" to FoodNames.parse(response.toString()),
                    "engineReused" to reused,
                    "initializationMs" to (if (reused) 0L else initialized - started), "inferenceMs" to ended - initialized,
                    "processPssMbAfter" to android.os.Debug.getPss() / 1024.0,
                    "backend" to "GPU · 이미지 GPU", "runtime" to "LiteRT-LM 0.16.1")
            }
        }
    }

    private fun finish(result: MethodChannel.Result, value: Any?) {
        stage = "대기"; busy.set(false)
        activity.runOnUiThread { if (!disposed) result.success(value) }
    }
    private fun fail(result: MethodChannel.Result, error: Exception) {
        stage = "대기"; busy.set(false)
        activity.runOnUiThread { if (!disposed) result.error("LOCAL_MODEL_FAILED", error.message ?: "분석에 실패했습니다.", null) }
    }
    private fun releaseEngine() {
        modelLoaded = false
        runCatching { engines.release() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        synchronized(conversationLock) { activeConversation?.cancelProcess() }
        channel.setMethodCallHandler(null)
        watchdog.shutdownNow()
        worker.execute { releaseEngine() }
        worker.shutdown()
    }
}

package com.example.foodify.local

import android.app.Activity
import android.graphics.ImageDecoder
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Boundary for a future validated Android segmenter; never returns fabricated masks. */
class SegmentationBridge(private val activity: Activity, messenger: BinaryMessenger) {
    private val executor = Executors.newSingleThreadExecutor()
    private val channel = MethodChannel(messenger, "foodify/segmentation")
    @Volatile private var closed = false
    init {
        channel.setMethodCallHandler { call, result ->
            when(call.method) {
                "segment" -> result.error("MODEL_NOT_READY", "Android 영역 분할 모델을 준비 중입니다. 직접 영역을 지정할 수 있습니다.", null)
                "prepare" -> {
                    val path = call.argument<String>("path")
                    executor.execute {
                        try {
                            check(android.os.Build.VERSION.SDK_INT >= 28) { "Android 9 이상에서 사용할 수 있습니다." }
                            val image = File(requireNotNull(path)).canonicalFile
                            val roots = listOf(activity.cacheDir, activity.filesDir).map { it.canonicalFile.toPath() }
                            require(roots.any { image.toPath().startsWith(it) } && image.length() in 1..20971520L) { "앱에서 촬영하거나 선택한 사진을 사용해 주세요." }
                            var width=0; var height=0
                            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(image)) { decoder, info, _ ->
                                width=info.size.width; height=info.size.height
                                require(width.toLong()*height <= 20_000_000) { "사진 해상도를 줄여 주세요." }
                                decoder.setTargetSize(1,1)
                                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            bitmap.recycle()
                            val hash=MessageDigest.getInstance("SHA-256")
                            image.inputStream().use { input ->
                                val buffer=ByteArray(65536)
                                while(true) { val count=input.read(buffer); if(count<0) break; hash.update(buffer,0,count) }
                            }
                            val fingerprint=hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            val frame=image.parentFile!!
                            val arRoot=File(activity.filesDir,"ar-diagnostics").canonicalFile.toPath()
                            val isAr=image.toPath().startsWith(arRoot) && image.name=="analysis.jpg" &&
                                listOf("metadata.json","depth.u16le","confidence.u8").all { File(frame,it).isFile }
                            val data=mutableMapOf<String,Any>("schemaVersion" to 1,"imageFingerprint" to fingerprint,
                                "imageWidth" to width,"imageHeight" to height,
                                "coordinateSpace" to if(isAr) "cpu_image_normalized" else "display_image_normalized",
                                "automaticAvailable" to false)
                            if(isAr) {
                                val digest=MessageDigest.getInstance("SHA-256")
                                for(name in listOf("metadata.json","depth.u16le","confidence.u8")) digest.update(File(frame,name).readBytes())
                                data["frameFingerprint"]=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            }
                            activity.runOnUiThread { if(!closed) result.success(data) }
                        } catch(e: Exception) {
                            activity.runOnUiThread { if(!closed) result.error("PREPARE_FAILED",e.message,null) }
                        }
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
    fun dispose() { closed=true; channel.setMethodCallHandler(null); executor.shutdown() }
}

package com.example.foodify.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Exports one matched RGB/depth frame. Scene depth is not food height or mass. */
object ArCaptureExport {
    fun export(root: File, name: String): Map<String, Any> {
        require(name.matches(Regex("[a-fA-F0-9-]{36}")))
        val directory = File(root, name)
        val session = JSONObject(File(directory, "session.json").readText())
        require(session.isNull("error")) { "AR 저장이 완료되지 않았습니다. 다시 촬영해 주세요." }
        val candidates = directory.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { frame ->
            runCatching {
                val meta = JSONObject(File(frame, "metadata.json").readText())
                val frameTime = meta.getString("frameTimestampNs").toLong()
                val depthAge = abs(frameTime - meta.getString("depthTimestampNs").toLong()) / 1e6
                val imageAge = abs(frameTime - meta.getString("cameraTimestampNs").toLong()) / 1e6
                val pixels = meta.getInt("depthWidth") * meta.getInt("depthHeight")
                val coverage = meta.getInt("confidentPixels").toDouble() / pixels
                if (depthAge > 100 || imageAge > 50 || coverage < 0.05 || meta.getString("trackingState") != "TRACKING") null
                else Triple(frame, meta, coverage)
            }.getOrNull()
        }
        val (frame, meta, coverage) = candidates.maxByOrNull { it.third }
            ?: error("사진과 일치하는 유효 깊이가 부족합니다. 밝은 곳에서 다시 촬영하거나 사진 분석을 이용해 주세요.")
        val dimensions = meta.getJSONObject("cpuIntrinsics").getJSONArray("imageDimensions")
        val width = dimensions.getInt(0); val height = dimensions.getInt(1)
        require(width % 2 == 0 && height % 2 == 0)
        val y = File(frame, "camera-y.u8").readBytes()
        val u = File(frame, "camera-u.u8").readBytes()
        val v = File(frame, "camera-v.u8").readBytes()
        require(y.size == width * height && u.size == y.size / 4 && v.size == u.size)
        val nv21 = ByteArray(y.size + u.size + v.size)
        y.copyInto(nv21)
        for (i in u.indices) { nv21[y.size + i * 2] = v[i]; nv21[y.size + i * 2 + 1] = u[i] }
        val photo = File(frame, "analysis.jpg")
        photo.outputStream().use { check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 90, it)) }
        val stats = DepthData.stats(File(frame, "depth.u16le").readBytes(), File(frame, "confidence.u8").readBytes())
        val intrinsics = meta.getJSONObject("cpuIntrinsics")
        val capture = JSONObject().put("schemaVersion", 1).put("method", "ar_assisted_photo")
            .put("volumeValidated", false).put("device", meta.getString("device"))
            .put("ar", JSONObject().put("imageIndex", 0).put("sceneMedianDepthMm", stats.medianMm)
                .put("confidentPixelFraction", coverage).put("confidenceThreshold", DepthData.CONFIDENCE_THRESHOLD)
                .put("imageWidth", width).put("imageHeight", height)
                .put("focalLengthPixels", intrinsics.getJSONArray("focalLength"))
                .put("principalPointPixels", intrinsics.getJSONArray("principalPoint"))
                .put("depthAgeMs", abs(meta.getString("frameTimestampNs").toLong() - meta.getString("depthTimestampNs").toLong()) / 1e6)
                .put("cameraAgeMs", abs(meta.getString("frameTimestampNs").toLong() - meta.getString("cameraTimestampNs").toLong()) / 1e6))
        return mapOf("path" to photo.absolutePath, "captureInfo" to capture.toString())
    }
}

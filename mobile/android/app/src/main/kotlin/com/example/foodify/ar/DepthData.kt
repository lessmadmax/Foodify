package com.example.foodify.ar

import java.nio.ByteBuffer

/** Plain Kotlin helpers: packed rows, uint16 millimetres and confidence 0..255. */
object DepthData {
    const val CONFIDENCE_THRESHOLD = 128

    fun pack(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int,
             pixelStride: Int, bytesPerPixel: Int): ByteArray {
        require(width > 0 && height > 0 && pixelStride >= bytesPerPixel && bytesPerPixel > 0)
        require(rowStride >= (width - 1) * pixelStride + bytesPerPixel)
        val source = buffer.duplicate()
        val base = source.position()
        require(base.toLong() + (height - 1L) * rowStride +
                (width - 1L) * pixelStride + bytesPerPixel <= source.limit())
        val output = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), bytesPerPixel))
        for (y in 0 until height) for (x in 0 until width) for (b in 0 until bytesPerPixel) {
            output[(y * width + x) * bytesPerPixel + b] =
                source.get(base + y * rowStride + x * pixelStride + b)
        }
        return output
    }

    fun millimetres(depth: ByteArray, index: Int): Int =
        (depth[index * 2].toInt() and 255) or ((depth[index * 2 + 1].toInt() and 255) shl 8)

    data class Stats(val valid: Int, val confident: Int, val total: Int,
                     val medianMm: Int?, val meanConfidence: Double)

    fun stats(depth: ByteArray, confidence: ByteArray): Stats {
        require(depth.size == confidence.size * 2 && confidence.isNotEmpty())
        var valid = 0
        var sum = 0L
        val selected = ArrayList<Int>()
        for (i in confidence.indices) {
            val mm = millimetres(depth, i)
            if (mm == 0) continue
            valid++
            val c = confidence[i].toInt() and 255
            sum += c
            if (c >= CONFIDENCE_THRESHOLD) selected.add(mm)
        }
        selected.sort()
        return Stats(valid, selected.size, confidence.size,
            if (selected.isEmpty()) null else selected[selected.size / 2],
            if (valid == 0) 0.0 else sum.toDouble() / valid / 255.0)
    }
}

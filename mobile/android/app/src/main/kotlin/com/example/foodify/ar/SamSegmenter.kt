package com.example.foodify.ar

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * MobileSAM (vit_t) on the device: user taps on the food (and optionally a box) -> food mask -> outline
 * polygons (a draft the user still confirms). The photo is encoded once and cached, so each extra tap only
 * runs the decoder. Models ship in assets/mobilesam and are copied once to app storage, checked by SHA-256.
 * S23 timing (2026-11-10): encoder ~0.93 s, decoder ~0.05 s, 4 threads.
 */
class SamSegmenter(private val activity:Activity) {
    private val env=OrtEnvironment.getEnvironment()
    private var encoder:OrtSession?=null
    private var decoder:OrtSession?=null
    /** Cached encoding of the last photo: key (path, size, mtime), embeddings 1x256x64x64, photo size. */
    private var cacheKey:String?=null
    private var cacheEmbeddings:FloatArray?=null
    private var cacheWidth=0;private var cacheHeight=0

    private fun model(name:String,manifest:JSONObject):File {
        val entry=manifest.getJSONObject("files").getJSONObject(name)
        val dir=File(activity.filesDir,"models/mobilesam").apply { mkdirs() }
        val file=File(dir,name)
        if(file.isFile && file.length()==entry.getLong("bytes")) return file
        val partial=File(dir,"$name.partial")
        try {
            activity.assets.open("mobilesam/$name").use { source -> partial.outputStream().use { source.copyTo(it) } }
            val hash=MessageDigest.getInstance("SHA-256")
            partial.inputStream().use { stream -> val buffer=ByteArray(1048576);while(true) {val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
            check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }==entry.getString("sha256")) { "영역 분할 모델 파일 검증에 실패했습니다." }
            java.nio.file.Files.move(partial.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
        return file
    }
    private fun sessions():Pair<OrtSession,OrtSession> {
        if(encoder==null || decoder==null) {
            val manifest=JSONObject(activity.assets.open("mobilesam/manifest.json").bufferedReader().use { it.readText() })
            val options=OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            options.use {
                encoder=env.createSession(model("mobile_sam_encoder.onnx",manifest).path,it)
                decoder=env.createSession(model("mobile_sam_decoder.onnx",manifest).path,it)
            }
        }
        return Pair(encoder!!,decoder!!)
    }
    /** Encodes [photo] unless it is the cached one; returns (embeddings, width, height). */
    private fun encode(photo:File,enc:OrtSession):Triple<FloatArray,Int,Int> {
        val key="${photo.canonicalPath}|${photo.length()}|${photo.lastModified()}"
        cacheEmbeddings?.let { if(cacheKey==key) return Triple(it,cacheWidth,cacheHeight) }
        val bitmap=BitmapFactory.decodeFile(photo.path) ?: error("사진을 읽지 못했습니다.")
        val w=bitmap.width;val h=bitmap.height
        val (nw,nh)=SamGeometry.resized(w,h)
        val scaled=Bitmap.createScaledBitmap(bitmap,nw,nh,true)
        val pixels=IntArray(nw*nh);scaled.getPixels(pixels,0,nw,0,0,nw,nh)
        if(scaled!==bitmap) scaled.recycle();bitmap.recycle()
        val embeddings=OnnxTensor.createTensor(env,FloatBuffer.wrap(SamGeometry.input(pixels,nw,nh)),longArrayOf(1,3,SamGeometry.SIZE.toLong(),SamGeometry.SIZE.toLong())).use { image ->
            enc.run(mapOf("image" to image)).use { out -> val b=(out[0] as OnnxTensor).floatBuffer;FloatArray(b.remaining()).also { b.get(it) } }
        }
        require(embeddings.size==256*64*64) { "영역 분할 모델 출력 크기를 확인해 주세요." }
        cacheKey=key;cacheEmbeddings=embeddings;cacheWidth=w;cacheHeight=h
        return Triple(embeddings,w,h)
    }
    /** Encodes the photo ahead of the first tap, so the first cut is fast. */
    @Synchronized fun prepare(photo:File):Map<String,Any?> {
        val started=System.nanoTime();val (_,w,h)=encode(photo,sessions().first)
        return mapOf("prepared" to true,"imageWidth" to w,"imageHeight" to h,"elapsedMs" to (System.nanoTime()-started)/1e6)
    }
    /** Food mask from taps ([points]: u, v, label 1 food / 0 not food) and an optional normalized [box]. */
    @Synchronized fun segment(photo:File,points:List<Triple<Double,Double,Int>>,box:DoubleArray?=null):Map<String,Any?> {
        val (enc,dec)=sessions()
        val started=System.nanoTime()
        val (embeddings,w,h)=encode(photo,enc)
        val (coords,labels)=SamGeometry.prompt(points,box,w,h)
        val n=labels.size.toLong()
        val inputs=mapOf(
            "image_embeddings" to OnnxTensor.createTensor(env,FloatBuffer.wrap(embeddings),longArrayOf(1,256,64,64)),
            "point_coords" to OnnxTensor.createTensor(env,FloatBuffer.wrap(coords),longArrayOf(1,n,2)),
            "point_labels" to OnnxTensor.createTensor(env,FloatBuffer.wrap(labels),longArrayOf(1,n)),
            "mask_input" to OnnxTensor.createTensor(env,FloatBuffer.allocate(256*256),longArrayOf(1,1,256,256)),
            "has_mask_input" to OnnxTensor.createTensor(env,FloatBuffer.wrap(floatArrayOf(0f)),longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env,FloatBuffer.wrap(floatArrayOf(h.toFloat(),w.toFloat())),longArrayOf(2)))
        val mask:BooleanArray;val score:Float
        try {
            dec.run(inputs).use { out ->
                val logits=(out[0] as OnnxTensor).floatBuffer
                require(logits.remaining()==w*h) { "영역 분할 결과 크기가 사진과 다릅니다." }
                mask=BooleanArray(w*h) { logits.get(it)>0f }
                score=(out[1] as OnnxTensor).floatBuffer.get(0)
            }
        } finally { inputs.values.forEach { it.close() } }
        val polygons=SamGeometry.polygons(mask,w,h)
        require(polygons.isNotEmpty()) { "누른 곳에서 음식 영역을 찾지 못했습니다. 음식 위를 다시 눌러 주세요." }
        return mapOf("source" to if(box==null) "tap_sam" else "ai_box_sam","foodPolygons" to polygons.map { p -> p.map { listOf(it.first,it.second) } },
            "maskPixels" to mask.count { it },"pieces" to polygons.size,"predictedIou" to score.toDouble(),
            "elapsedMs" to (System.nanoTime()-started)/1e6)
    }
    @Synchronized fun close() { encoder?.close();decoder?.close();encoder=null;decoder=null;cacheEmbeddings=null;cacheKey=null }
}

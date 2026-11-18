package com.example.foodify.ar

import android.app.Activity
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline measurement records. Scale labels never feed the geometry estimator. */
class ArResearchBridge(private val activity:Activity,messenger:BinaryMessenger) {
    private val channel=MethodChannel(messenger,"foodify/ar_research")
    private val worker=Executors.newSingleThreadExecutor()
    private val root=File(activity.filesDir,"ar-diagnostics").canonicalFile
    private val store=File(activity.filesDir,"ar-research").apply { mkdirs() }
    private val records=File(store,"records").apply { mkdirs() }
    private val models=File(store,"models").apply { mkdirs() }
    /** ARCore point confidence (0–1) below this is ignored for the feature-point distance. */
    private val FEATURE_MIN_CONFIDENCE=0.1
    /** MobileSAM, loaded on first automatic outline. */
    private val segmenterInit=lazy { SamSegmenter(activity) }
    private val segmenter by segmenterInit
    @Volatile private var closed=false
    private var exportResult:MethodChannel.Result?=null
    private val archive=File(activity.cacheDir,"foodify-ar-export.zip")
    init {
        channel.setMethodCallHandler { call,result ->
            if(call.method=="export") {
                if(exportResult!=null) {result.error("BUSY","내보내기 작업을 마무리해 주세요.",null);return@setMethodCallHandler}
                exportResult=result
                val ids=call.argument<List<String>>("ids") ?: emptyList()
                worker.execute {
                    try { makeArchive(ids);activity.runOnUiThread { if(!closed) activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {type="application/zip";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_TITLE,"foodify-ar-${System.currentTimeMillis()}.zip")},7403) } }
                    catch(e:Exception) { activity.runOnUiThread { exportResult=null;if(!closed)result.error("EXPORT_FAILED",e.message,null) } }
                };return@setMethodCallHandler
            }
            val input=JSONObject(com.google.gson.Gson().toJson(call.arguments ?: emptyMap<String,Any>()))
            worker.execute {
                try {
                    val answer:Any?=when(call.method) {
                        "sessions" -> root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-fA-F0-9-]{36}")) }.sortedByDescending { it.lastModified() }.take(200).map { mapOf("id" to it.name,"savedAt" to it.lastModified()) }
                        "frames" -> {
                            val session=input.getString("session");require(session.matches(Regex("[a-fA-F0-9-]{36}")))
                            File(root,session).listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("frame-[0-9]{2}")) && File(it,"metadata.json").exists() }.sortedBy { it.name }.map { frame ->
                                val meta=JSONObject(File(frame,"metadata.json").readText());mapOf("path" to frame.path,"id" to frame.name,"confidentFraction" to meta.optInt("confidentPixels").toDouble()/maxOf(1,meta.optInt("depthWidth")*meta.optInt("depthHeight")),"depthAgeMs" to kotlin.math.abs(meta.optString("frameTimestampNs","0").toDouble()-meta.optString("depthTimestampNs","0").toDouble())/1e6)
                            }
                        }
                        "prepare" -> prepare(frame(input.getString("framePath")))
                        "estimate" -> estimate(input)
                        "foodFromBox" -> foodFromBox(input)
                        "prepareSegment" -> segmenter.prepare(photo(frame(input.getString("framePath"))))
                        "segmentPoints" -> {
                            val taps=input.getJSONArray("points");require(taps.length() in 1..SamGeometry.MAX_POINTS) { "음식을 한 곳 이상 눌러 주세요." }
                            val points=(0 until taps.length()).map { val p=taps.getJSONArray(it);require(p.length()==3);Triple(p.getDouble(0),p.getDouble(1),p.getInt(2)) }
                            segmenter.segment(photo(frame(input.getString("framePath"))),points)
                        }
                        "scale" -> scale(input)
                        "save" -> save(input)
                        "records" -> refreshRecords().map { clean(it) }
                        "record" -> clean(readRecord(input.getString("id")))
                        "delete" -> { require(recordFile(input.getString("id")).delete());mapOf("deleted" to true) }
                        "calibrate" -> calibrate(input.getString("foodName"))
                        "summary" -> summary(input.getString("foodName"))
                        else -> error("지원하지 않는 측정 요청입니다.")
                    }
                    activity.runOnUiThread { if(!closed)result.success(answer) }
                } catch(e:Exception) {activity.runOnUiThread {if(!closed)result.error("AR_RESEARCH_FAILED",e.message ?: "측정 자료를 확인해 주세요.",null)}}
            }
        }
    }
    private fun clean(value:Any?):Any?=when(value) {
        null,JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { clean(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { clean(value.get(it)) }
        else -> value
    }
    private fun frame(path:String):File {
        var file=File(path).canonicalFile;if(file.isFile)file=file.parentFile!!
        require(file.toPath().startsWith(root.toPath()) && file.name.matches(Regex("frame-[0-9]{2}")) && file.parentFile!!.name.matches(Regex("[a-fA-F0-9-]{36}"))) { "앱에서 저장한 AR 프레임을 선택해 주세요." }
        return file
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fingerprint(frame:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        for(name in listOf("metadata.json","depth.u16le","confidence.u8"))digest.update(File(frame,name).readBytes())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun photo(frame:File):File {
        val meta=JSONObject(File(frame,"metadata.json").readText());val dims=meta.getJSONObject("cpuIntrinsics").getJSONArray("imageDimensions")
        val w=dims.getInt(0);val h=dims.getInt(1);require(w>0 && h>0 && w%2==0 && h%2==0 && w.toLong()*h<=20000000)
        val target=File(frame,"analysis.jpg");if(target.isFile)return target
        val y=File(frame,"camera-y.u8").readBytes();val u=File(frame,"camera-u.u8").readBytes();val v=File(frame,"camera-v.u8").readBytes()
        require(y.size==w*h && u.size==w*h/4 && v.size==u.size)
        val nv=ByteArray(y.size+u.size+v.size);y.copyInto(nv)
        for(i in u.indices) {nv[y.size+2*i]=v[i];nv[y.size+2*i+1]=u[i]}
        target.outputStream().use {check(YuvImage(nv,ImageFormat.NV21,w,h,null).compressToJpeg(Rect(0,0,w,h),90,it))}
        return target
    }
    private fun prepare(frame:File):Map<String,Any?> {
        val image=photo(frame);val meta=JSONObject(File(frame,"metadata.json").readText());val dims=meta.getJSONObject("cpuIntrinsics").getJSONArray("imageDimensions")
        return mapOf("framePath" to frame.path,"imagePath" to image.path,"frameFingerprint" to fingerprint(frame),"imageFingerprint" to hash(image),"imageWidth" to dims.getInt(0),"imageHeight" to dims.getInt(1),"metadata" to clean(meta))
    }
    private fun doubles(a:JSONArray)=DoubleArray(a.length()) {a.getDouble(it)}
    private fun readFrame(frame:File):ArVolume.Frame {
        val m=JSONObject(File(frame,"metadata.json").readText());require(m.getInt("schemaVersion")==1 && m.getString("depthUnit")=="millimetres_camera_z")
        val w=m.getInt("depthWidth");val h=m.getInt("depthHeight");require(w>=2 && h>=2 && w.toLong()*h<=1000000)
        val depth=File(frame,"depth.u16le").readBytes();val confidence=File(frame,"confidence.u8").readBytes();require(depth.size==w*h*2 && confidence.size==w*h)
        val buffer=ByteBuffer.wrap(depth).order(ByteOrder.LITTLE_ENDIAN);val z=IntArray(w*h) {buffer.short.toInt() and 65535};val conf=IntArray(w*h) {confidence[it].toInt() and 255}
        val intr=m.getJSONObject("cpuIntrinsics");val dim=intr.getJSONArray("imageDimensions");val focal=intr.getJSONArray("focalLength");val principal=intr.getJSONArray("principalPoint")
        return ArVolume.Frame(w,h,z,conf,dim.getInt(0),dim.getInt(1),focal.getDouble(0),focal.getDouble(1),principal.getDouble(0),principal.getDouble(1),doubles(m.getJSONArray("depthNormalizedCorners")),doubles(m.getJSONArray("correspondingCpuImageNormalizedCorners")),m.getString("trackingState")=="TRACKING",m.getString("frameTimestampNs").toLong(),m.getString("depthTimestampNs").toLong(),m.getString("confidenceTimestampNs").toLong(),m.getString("cameraTimestampNs").toLong(),m.optString("pairAcquisition")=="same_arcore_frame" || (m.has("cameraPlanes") && m.has("gpuIntrinsics")))
    }
    private fun observation(folder:File):ArFusion.Observation {
        val meta=JSONObject(File(folder,"metadata.json").readText())
        return ArFusion.Observation(folder.name,readFrame(folder),ArFusion.Pose(doubles(meta.getJSONArray("translationMetres")),doubles(meta.getJSONArray("rotationQuaternionXyzw"))))
    }
    private fun registeredEstimate(folder:File,food:List<List<Pair<Double,Double>>>,base:List<List<Pair<Double,Double>>>):Map<String,Any?> {
        val reference=observation(folder)
        val siblings=folder.parentFile!!.listFiles().orEmpty().filter {it.isDirectory && it.name.matches(Regex("frame-[0-9]{2}"))}.sortedBy {it.name}.take(10)
        val sources=siblings.mapNotNull {runCatching {observation(it)}.getOrNull()}
        val fused=ArFusion.combine(reference,sources)
        val original=ArVolume.estimate(reference.frame,food,base)
        val combined=ArVolume.estimate(fused.frame,food,base).toMutableMap()
        combined["singleFrameValidFoodFraction"]=original["validFoodPixelFraction"]
        combined["registeredFrameIds"]=fused.sourceIds
        combined["registeredFrameCount"]=fused.sourceIds.size
        combined["rejectedDepthConflictPixels"]=fused.rejectedConflictPixels
        combined["depthSource"]="registered_raw_depth"
        // Full depth is a comparison candidate, with no raw-confidence substitute.
        val meta=JSONObject(File(folder,"metadata.json").readText())
        val full=File(folder,"full-depth.u16le")
        combined["fullDepthComparisonAvailable"]=full.exists() && !meta.isNull("fullDepthTimestampNs")
        if(full.exists() && !meta.isNull("fullDepthTimestampNs")) {
            val bytes=full.readBytes();val w=meta.getInt("fullDepthWidth");val h=meta.getInt("fullDepthHeight")
            if(bytes.size==w*h*2) {
                val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val values=IntArray(w*h) {buffer.short.toInt() and 65535}
                combined["fullDepthNonzeroFraction"]=values.count {it>0}.toDouble()/values.size
                val mapping=ArFusion.Mapping(reference.frame.source,reference.frame.target)
                var foodPixels=0;var foodNonzero=0
                for(y in 0 until h)for(x in 0 until w) {
                    val uv=mapping.image((x+.5)/w,(y+.5)/h)
                    if(ArVolume.mask(uv.first,uv.second,food)) {foodPixels++;if(values[y*w+x]>0)foodNonzero++}
                }
                combined["fullDepthFoodNonzeroFraction"]=if(foodPixels==0)null else foodNonzero.toDouble()/foodPixels
                combined["fullDepthHasConfidenceImage"]=false
                combined["fullDepthUsedForWeight"]=false
                combined["fullDepthObservationAgeMs"]=(meta.getString("cameraTimestampNs").toDouble()-meta.getString("fullDepthTimestampNs").toDouble())/1e6
            }
        }
        return combined
    }
    private fun polygons(region:JSONObject,frame:File):List<List<Pair<Double,Double>>> {
        require(region.getString("coordinateSpace")=="cpu_image_normalized" && region.getString("frameFingerprint")==fingerprint(frame) && region.getString("imageFingerprint")==hash(photo(frame))) { "사진과 지정한 영역이 다릅니다. 다시 지정해 주세요." }
        val all=region.getJSONArray("foodPolygons");require(all.length() in 1..30)
        return (0 until all.length()).map { i-> val polygon=all.getJSONArray(i);require(polygon.length() in 3..200);(0 until polygon.length()).map {j->val point=polygon.getJSONArray(j);require(point.length()==2);Pair(point.getDouble(0),point.getDouble(1))} }
    }
    private fun modelFile(food:String)=File(models,MessageDigest.getInstance("SHA-256").digest(food.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }+".json")
    private fun sampleDigest(ids:List<String>):String {
        val samples=listRecords().associateBy {it.getString("id")}
        val content=ids.sorted().joinToString("\n") {id ->
            val r=samples[id]
            if(r==null) "$id:missing" else listOf(id,r.getString("foodName"),r.getString("portionGroup"),r.getString("split"),r.getDouble("scaleGrams"),r.getJSONObject("result").opt("volumeMl"),r.getString("frameFingerprint")).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
    }
    private fun currentModel(m:JSONObject):Boolean {
        val ids=m.getJSONArray("sampleIds");return m.optString("sampleDigest")==sampleDigest((0 until ids.length()).map {ids.getString(it)})
    }
    /** AI box → depth-refined food outline (image-normalized polygons as [x,y] lists). */
    private fun foodFromBox(input:JSONObject):Map<String,Any?> {
        val f=frame(input.getString("framePath"));val box=doubles(input.getJSONArray("box"))
        val result=ArVolume.foodFromBox(readFrame(f),box).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val polygons=result["foodPolygons"] as List<List<Pair<Double,Double>>>
        result["foodPolygons"]=polygons.map { polygon -> polygon.map { listOf(it.first,it.second) } }
        return result
    }
    /** Area × height method: plate distance and top-view food area on the multi-frame depth. */
    private fun scale(input:JSONObject):Map<String,Any?> {
        val f=frame(input.getString("framePath"))
        val food=polygons(input.getJSONObject("foodRegion"),f);val base=input.optJSONObject("baseRegion")?.let {polygons(it,f)} ?: emptyList()
        val reference=observation(f)
        val siblings=f.parentFile!!.listFiles().orEmpty().filter {it.isDirectory && it.name.matches(Regex("frame-[0-9]{2}"))}.sortedBy {it.name}.take(10)
        val fused=runCatching {ArFusion.combine(reference,siblings.mapNotNull {runCatching {observation(it)}.getOrNull()}).frame}.getOrDefault(reference.frame)
        val points=featurePoints(siblings,reference.pose)
        val vessel=input.optJSONObject("vesselReference")
        if(vessel!=null && vessel.optBoolean("usable",false)) {
            val rim=vessel.optJSONArray("rimPoints")
            val coordinates=if(rim==null) emptyList() else (0 until rim.length()).map {i->
                val p=rim.getJSONArray(i);Pair(p.getDouble(0),p.getDouble(1))}
            val detection=ArVessel.Detection(vessel.optString("vesselType"),vessel.optString("confidence"),coordinates)
            if(ArVessel.eligible(detection)) return ArVessel.scale(fused,food,detection,points)+mapOf("featurePointCount" to points.size)
        }
        return ArVolume.scale(fused,food,base,points)+mapOf("featurePointCount" to points.size,
            "vesselReference" to mapOf("applied" to false,"vesselType" to (vessel?.optString("vesselType") ?: "unknown"),
                "reason" to "vessel_not_available_or_uncertain","volumeValidated" to false))
    }
    /**
     * ARCore feature points of the session frames, one per id (latest frame wins), in the reference camera's
     * coordinates (mm). Older sessions without point-cloud files give none.
     */
    private fun featurePoints(frames:List<File>,reference:ArFusion.Pose):List<ArVolume.V> {
        val byId=linkedMapOf<Int,ArVolume.V>()
        for(folder in frames) runCatching {
            val p=File(folder,"point-cloud.f32le");val q=File(folder,"point-cloud-ids.i32le")
            if(!p.exists() || !q.exists()) return@runCatching
            val pb=ByteBuffer.wrap(p.readBytes()).order(ByteOrder.LITTLE_ENDIAN);val qb=ByteBuffer.wrap(q.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n=minOf(pb.remaining()/16,qb.remaining()/4)
            repeat(n) {
                val x=pb.float.toDouble();val y=pb.float.toDouble();val z=pb.float.toDouble();val confidence=pb.float.toDouble();val id=qb.int
                if(confidence>=FEATURE_MIN_CONFIDENCE && x.isFinite() && y.isFinite() && z.isFinite()) byId[id]=reference.camera(ArVolume.V(x*1000,y*1000,z*1000))
            }
        }
        return byId.values.toList()
    }
    private fun estimate(input:JSONObject):Map<String,Any?> {
        val f=frame(input.getString("framePath"));val food=input.optString("foodName").trim();var result:Map<String,Any?>
        // No baseRegion: ArVolume picks the plate band around the food automatically.
        result=if(input.optJSONObject("foodRegion")==null) mapOf("status" to "ANNOTATION_REQUIRED","volumeValidated" to false,"holdReasons" to listOf("음식 영역을 지정해 주세요."),"volumeMl" to null,"observedVolumeMl" to null)
        else try {registeredEstimate(f,polygons(input.getJSONObject("foodRegion"),f),input.optJSONObject("baseRegion")?.let {polygons(it,f)} ?: emptyList())} catch(e:Exception) {mapOf("status" to "REVIEW_REQUIRED","volumeValidated" to false,"holdReasons" to listOf(e.message ?: "영역·깊이 자료 확인 필요"),"volumeMl" to null,"observedVolumeMl" to null)}
        val out=result.toMutableMap();out["algorithmVersion"]=ArVolume.ALGORITHM_VERSION;val model=modelFile(food)
        out.putAll(ArDensity.fields(food,out["volumeMl"] as? Double,out["observedVolumeMl"] as? Double))
        if(model.exists()) {
            val m=JSONObject(model.readText());out["calibrationId"]=m.getString("id");out["densityGPerMl"]=m.getDouble("densityGPerMl")
            val volume=out["volumeMl"] as? Double
            if(!currentModel(m)) out["calibrationMessage"]="보정 자료가 변경되었습니다. 환산식을 다시 생성해 주세요."
            else if(volume!=null)out["estimatedGrams"]=volume*m.getDouble("densityGPerMl")
        }
        return out
    }
    private fun recordFile(id:String):File {require(id.matches(Regex("[a-fA-F0-9-]{36}")));return File(records,"$id.json")}
    private fun readRecord(id:String)=JSONObject(recordFile(id).readText())
    private fun listRecords()=records.listFiles().orEmpty().filter {it.extension=="json"}.mapNotNull {runCatching {JSONObject(it.readText())}.getOrNull()}.sortedByDescending {it.getLong("savedAt")}
    /** Recalculate legacy results while preserving original labels, regions and capture times. */
    private fun refreshRecords():List<JSONObject> {
        for(record in listRecords()) {
            if(record.getJSONObject("result").optInt("algorithmVersion",1)>=ArVolume.ALGORITHM_VERSION) continue
            val id=record.getString("id")
            val backup=File(store,"backups").apply {mkdirs()}.resolve("$id-v${record.getJSONObject("result").optInt("algorithmVersion",1)}.json")
            if(!backup.exists()) recordFile(id).copyTo(backup)
            val calculated=estimate(record).toMutableMap()
            val scale=record.getDouble("scaleGrams");val weight=calculated["estimatedGrams"] as? Double
            calculated["scaleGrams"]=scale
            calculated["absoluteErrorGrams"]=weight?.let {kotlin.math.abs(it-scale)}
            calculated["relativeErrorPercent"]=weight?.let {kotlin.math.abs(it-scale)/scale*100}
            tableErrors(calculated,scale)
            record.put("result",JSONObject(com.google.gson.GsonBuilder().serializeNulls().create().toJson(calculated)))
            record.put("recomputedAtEpochMs",System.currentTimeMillis())
            val temp=File(records,"$id.partial");temp.writeText(record.toString())
            java.nio.file.Files.move(temp.toPath(),recordFile(id).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return listRecords()
    }
    /** Signed error so systematic under/over-estimation stays visible in exports. */
    private fun tableErrors(result:MutableMap<String,Any?>,scale:Double) {
        for((source,prefix) in listOf("tableEstimatedGrams" to "table","tableEstimatedObservedGrams" to "tableObserved")) {
            val grams=result[source] as? Double
            result["${prefix}SignedErrorPercent"]=grams?.let {(it-scale)/scale*100}
        }
    }
    private fun samples()=listRecords().map {r->val value=r.getJSONObject("result").optDouble("volumeMl",Double.NaN);ArCalibration.Sample(r.getString("id"),r.getString("foodName"),r.getString("portionGroup"),r.getString("split"),if(value.isFinite() && value>0)value else null,r.getDouble("scaleGrams"))}
    private fun save(input:JSONObject):Map<String,Any?> {
        val f=frame(input.getString("framePath"));val food=input.getString("foodName").trim();val group=input.getString("portionGroup").trim();val split=input.getString("split");val scale=input.getDouble("scaleGrams")
        require(food.length in 1..80 && group.length in 1..80 && split in listOf("CALIBRATION","EVALUATION") && scale.isFinite() && scale>0 && scale<=100000) { "음식명·제공량 그룹·양수의 실측 중량을 입력해 주세요." }
        val prior=listRecords();require(prior.none {it.getString("foodName")==food && it.getString("portionGroup")==group && it.getString("split")!=split}) { "같은 제공량 그룹은 보정 또는 평가 중 하나에만 저장해 주세요." }
        val profile=modelFile(food)
        if(split=="EVALUATION" && profile.exists()) {val groups=JSONObject(profile.readText()).getJSONArray("calibrationGroups");require((0 until groups.length()).none {groups.getString(it)==group}) { "보정에 사용한 그룹은 평가할 수 없습니다." }}
        val frameId=fingerprint(f)
        val old=prior.firstOrNull {it.getString("frameFingerprint")==frameId && it.getString("foodName")==food && it.getString("portionGroup")==group && it.getString("split")==split}
        val id=old?.getString("id") ?: UUID.randomUUID().toString()
        val result=estimate(input).toMutableMap();val weight=result["estimatedGrams"] as? Double
        result["scaleGrams"]=scale;result["absoluteErrorGrams"]=weight?.let {kotlin.math.abs(it-scale)};result["relativeErrorPercent"]=weight?.let {kotlin.math.abs(it-scale)/scale*100}
        tableErrors(result,scale)
        val data=linkedMapOf<String,Any?>("schemaVersion" to 1,"id" to id,"savedAt" to System.currentTimeMillis(),"foodName" to food,"portionGroup" to group,"split" to split,"scaleGrams" to scale,"framePath" to f.path,"frameRelativePath" to f.relativeTo(root).invariantSeparatorsPath,"frameFingerprint" to frameId,"foodRegion" to clean(input.optJSONObject("foodRegion")),"baseRegion" to clean(input.optJSONObject("baseRegion")),"result" to result,"device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val temp=File(records,"$id.partial");temp.writeText(com.google.gson.GsonBuilder().serializeNulls().create().toJson(data));java.nio.file.Files.move(temp.toPath(),recordFile(id).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return data
    }
    private fun calibrate(foodName:String):Map<String,Any?> {
        val food=foodName.trim();val fitted=ArCalibration.fit(food,samples());val result=mapOf("id" to UUID.randomUUID().toString(),"foodName" to food,"createdAt" to System.currentTimeMillis(),"densityGPerMl" to fitted.density,"calibrationGroups" to fitted.groups.toList(),"sampleIds" to fitted.samples,"sampleDigest" to sampleDigest(fitted.samples))
        val file=modelFile(food);val temp=File(models,file.name+".partial");temp.writeText(com.google.gson.Gson().toJson(result));java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);return result
    }
    private fun summary(foodName:String):Map<String,Any?> {
        val food=foodName.trim();val data=samples();val path=modelFile(food)
        if(!path.exists())return mapOf("records" to data.count {it.food==food},"message" to "보정용 제공량 그룹 3개를 확보한 뒤 환산식을 생성해 주세요.")
        val m=JSONObject(path.readText());if(!currentModel(m))return mapOf("message" to "보정 자료가 변경되었습니다. 환산식을 다시 생성해 주세요.");val groups=m.getJSONArray("calibrationGroups");val ids=m.getJSONArray("sampleIds")
        val model=ArCalibration.Model(m.getDouble("densityGPerMl"),(0 until groups.length()).map {groups.getString(it)}.toSet(),(0 until ids.length()).map {ids.getString(it)})
        return ArCalibration.evaluate(food,data,model)+mapOf("calibration" to clean(m),"records" to data.count {it.food==food})
    }
    private fun makeArchive(ids:List<String>) {
        require(ids.isNotEmpty() && ids.size<=500) { "내보낼 기록을 선택해 주세요." }
        val files=linkedMapOf<String,File>();val exportRecords=ids.distinct().map {readRecord(it)}
        for(r in exportRecords) {
            val id=r.getString("id");files["records/$id.json"]=recordFile(id)
            for(previous in File(store,"backups").listFiles().orEmpty().filter {it.isFile && it.name.startsWith("$id-v") && it.extension=="json"})files["previous-records/${previous.name}"]=previous
            val frame=frame(r.getString("framePath"));for(file in frame.listFiles().orEmpty().filter {it.isFile})files["sessions/${frame.relativeTo(root).invariantSeparatorsPath}/${file.name}"]=file
            val used=r.getJSONObject("result").optJSONArray("registeredFrameIds")
            if(used!=null)for(i in 0 until used.length()) {
                val name=used.getString(i);require(name.matches(Regex("frame-[0-9]{2}")))
                val sibling=this.frame(File(frame.parentFile,name).path)
                for(file in sibling.listFiles().orEmpty().filter {it.isFile})files["sessions/${sibling.relativeTo(root).invariantSeparatorsPath}/${file.name}"]=file
            }
            val session=File(frame.parentFile,"session.json");if(session.exists())files["sessions/${frame.parentFile!!.name}/session.json"]=session
            val calibration=modelFile(r.getString("foodName"));if(calibration.exists())files["calibrations/${calibration.name}"]=calibration
        }
        require(files.values.sumOf {it.length()}<=500L*1024*1024) { "자료가 500MB를 넘습니다. 기록을 나누어 내보내 주세요." }
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(com.google.gson.Gson().toJson(mapOf("schemaVersion" to 1,"recordIds" to ids.distinct(),"coordinateSpace" to "cpu_image_normalized","depthUnit" to "millimetres_camera_z")).toByteArray());zip.closeEntry()
            for((name,file) in files) {check(!closed);zip.putNextEntry(ZipEntry(name));file.inputStream().use {it.copyTo(zip)};zip.closeEntry()}
        }
    }
    fun onFileResult(code:Int,intent:Intent?) {
        val result=exportResult ?: return;exportResult=null
        val uri=intent?.data
        if(code!=Activity.RESULT_OK || uri==null) {result.success(mapOf("saved" to false));return}
        worker.execute {
            try {activity.contentResolver.openOutputStream(uri,"wt").use { output -> requireNotNull(output);archive.inputStream().use {it.copyTo(output)} };activity.runOnUiThread {if(!closed)result.success(mapOf("saved" to true))}}
            catch(e:Exception) {activity.runOnUiThread {if(!closed)result.error("EXPORT_FAILED",e.message,null)}}
        }
    }
    fun dispose() {
        closed=true;channel.setMethodCallHandler(null)
        if(segmenterInit.isInitialized()) worker.execute { segmenter.close() }
        worker.shutdown()
    }
}

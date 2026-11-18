package com.example.foodify.ar

import kotlin.math.*

/** AI locates the rim; AR measures it. A category depth is only a provisional flat-bottom model. */
object ArVessel {
    val depthsCm=mapOf("rice_bowl" to 5.8,"flat_plate" to .8,"soup_bowl" to 3.0,"deep_plate" to 3.5)
    data class Detection(val type:String,val confidence:String,val rim:List<Pair<Double,Double>>)
    fun eligible(d:Detection)=d.type in depthsCm && d.confidence=="high" && d.rim.size in 8..24
    private fun polygonArea(p:List<Pair<Double,Double>>)=abs(p.indices.sumOf {i->val a=p[i];val b=p[(i+1)%p.size];a.first*b.second-b.first*a.second})/2
    private fun check(value:Boolean) {require(value) {"측정 기준면을 안정적으로 확인하지 못했습니다. 밝은 곳에서 바로 위로 다시 촬영해 주세요."}}
    fun scale(f:ArVolume.Frame,food:List<List<Pair<Double,Double>>>,d:Detection,
              features:List<ArVolume.V> = emptyList()):Map<String,Any?> {
        check(eligible(d))
        check(f.tracking && f.width*f.height==f.depth.size && f.depth.size==f.confidence.size && f.fx>0 && f.fy>0)
        check(d.rim.all {it.first.isFinite()&&it.second.isFinite()&&it.first in 0.0..1.0&&it.second in 0.0..1.0})
        check(d.rim.distinct().size==d.rim.size && polygonArea(d.rim) in .005..0.9)
        // Require ordered convex rim points. Reject crossing/degenerate AI polygons.
        val turns=d.rim.indices.map {i-> val a=d.rim[i];val b=d.rim[(i+1)%d.rim.size];val c=d.rim[(i+2)%d.rim.size]
            (b.first-a.first)*(c.second-b.second)-(b.second-a.second)*(c.first-b.first)}
        check(turns.all {it>1e-8}||turns.all {it< -1e-8})
        check(abs(polygonArea(d.rim)-polygonArea(ArVolume.convexHull(d.rim)))<1e-6)
        val region=listOf(d.rim)
        check(food.isNotEmpty()&&food.all {p->p.size>=3&&p.all {ArVolume.mask(it.first,it.second,region)}})
        val mapping=ArFusion.Mapping(f.source,f.target)
        fun xyz(u:Double,v:Double,z:Double)=ArVolume.V((u*f.imageWidth-f.cx)/f.fx*z,(v*f.imageHeight-f.cy)/f.fy*z,z)
        fun uv(p:ArVolume.V)=Pair((p.x/p.z*f.fx+f.cx)/f.imageWidth,(p.y/p.z*f.fy+f.cy)/f.imageHeight)
        fun near(u:Double,v:Double)=d.rim.indices.minByOrNull {i->hypot((u-d.rim[i].first)*f.imageWidth,(v-d.rim[i].second)*f.imageHeight)}!!
        fun distance(u:Double,v:Double,i:Int)=hypot((u-d.rim[i].first)*f.imageWidth,(v-d.rim[i].second)*f.imageHeight)
        val rimPoints=mutableListOf<ArVolume.V>();val rimSupport=mutableSetOf<Int>()
        val foodPoints=mutableListOf<ArVolume.V>();var foodPixels=0
        for(y in 0 until f.height)for(x in 0 until f.width) {
            val i=y*f.width+x;val (u,v)=mapping.image((x+.5)/f.width,(y+.5)/f.height)
            val isFood=ArVolume.mask(u,v,food)
            if(isFood)foodPixels++
            if(u !in 0.0..1.0||v !in 0.0..1.0||f.depth[i]<=0||f.confidence[i]<128)continue
            val p=xyz(u,v,f.depth[i].toDouble())
            if(isFood)foodPoints.add(p)
            val k=near(u,v)
            if(!isFood && distance(u,v,k)<=f.imageWidth*.008) {rimPoints.add(p);rimSupport.add(k)}
        }
        var rimSource="DEPTH"
        if(rimPoints.size<20 || rimSupport.size<ceil(d.rim.size*.6).toInt()) {
            rimPoints.clear();rimSupport.clear();rimSource="FEATURE_POINTS"
            for(p in features)if(p.z>0) {
                val (u,v)=uv(p);val k=near(u,v)
                if(!ArVolume.mask(u,v,food)&&distance(u,v,k)<=f.imageWidth*.008) {rimPoints.add(p);rimSupport.add(k)}
            }
        }
        check(rimPoints.size>=12 && rimSupport.size>=ceil(d.rim.size*.6).toInt())
        val plane=ArVolume.fitPlane(rimPoints)
        check(plane.rmse<=3.0 && plane.inliers>=.8 && -plane.normal.z>=cos(Math.toRadians(10.0)))
        val bottomOffset=plane.offset+depthsCm.getValue(d.type)*10
        val center=food.flatten().let {ps->Pair(ps.map {it.first}.average(),ps.map {it.second}.average())}
        val ray=xyz(center.first,center.second,1.0)
        val rimDistance= -plane.offset/plane.normal.dot(ray)
        val bottomDistance= -bottomOffset/plane.normal.dot(ray)
        check(rimDistance in 100.0..1000.0 && bottomDistance in 100.0..1000.0)
        val area=food.sumOf(::polygonArea)*f.imageWidth*f.imageHeight*(bottomDistance/f.fx)*(bottomDistance/f.fy)/100
        val useDepth=foodPoints.size>=20 && foodPixels>0 && foodPoints.size.toDouble()/foodPixels>=.3
        val samples=if(useDepth)foodPoints else features.filter {p-> p.z>0 && uv(p).let {ArVolume.mask(it.first,it.second,food)}}
        // Sparse points on a single textured corner do not represent the whole dish.
        val cells=samples.map {p->uv(p).let {Pair((it.first*8).toInt(),(it.second*8).toInt())}}.toSet()
        val enough=if(useDepth)true else samples.size>=12 && cells.size>=4
        val signed=samples.map {plane.normal.dot(it)+bottomOffset}
        check(!enough || signed.count {it< -5}.toDouble()/signed.size<=.2)
        val height=if(enough)signed.map {it.coerceIn(0.0,300.0)}.average().takeIf {it>=1}?.div(10) else null
        val mm=bottomDistance/((f.fx+f.fy)/2)
        val reference=mapOf("vesselType" to d.type,"internalDepthCm" to depthsCm.getValue(d.type),
            "depthBasis" to "internet_category_default_v1","applied" to true,"rimSource" to rimSource,
            "rimDistanceMm" to rimDistance,"bottomDistanceMm" to bottomDistance,"rimSamples" to rimPoints.size,
            "rimRmseMm" to plane.rmse,"rimInliers" to plane.inliers,"rimOffsetMm" to plane.offset,
            "rimPoints" to d.rim.map {listOf(it.first,it.second)},
            "bottomModel" to "flat_bottom_approximation","volumeValidated" to false)
        return mapOf("distanceMm" to bottomDistance,"distanceSource" to "vessel_rim_default_depth",
            "areaCm2" to area,"mmPerPixel" to mm,"photoWidthCm" to f.imageWidth*mm/10,"photoHeightCm" to f.imageHeight*mm/10,
            "measuredHeightCm" to height,"measuredHeightSource" to if(useDepth)"DEPTH" else "FEATURE_POINTS",
            "measuredHeightSamples" to samples.size,"vesselReference" to reference)
    }
}

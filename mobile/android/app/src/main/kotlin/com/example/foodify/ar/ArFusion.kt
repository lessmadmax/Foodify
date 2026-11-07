package com.example.foodify.ar

import kotlin.math.*

/** Direct reprojection only. Unobserved cells remain empty; no hole filling. */
object ArFusion {
    data class Pose(val translation:DoubleArray,val quaternion:DoubleArray) {
        init {require(translation.size==3 && quaternion.size==4 && (translation+quaternion).all {it.isFinite()});require(quaternion.sumOf {it*it}>.99 && quaternion.sumOf {it*it}<1.01)}
        private fun rotate(v:ArVolume.V,inverse:Boolean):ArVolume.V {
            val n=sqrt(quaternion.sumOf {it*it});val sign=if(inverse)-1 else 1
            val q=ArVolume.V(sign*quaternion[0]/n,sign*quaternion[1]/n,sign*quaternion[2]/n);val w=quaternion[3]/n
            val t=q.cross(v)*2.0
            return v+t*w+q.cross(t)
        }
        fun world(cv:ArVolume.V)=rotate(ArVolume.V(cv.x,-cv.y,-cv.z),false)+ArVolume.V(translation[0]*1000,translation[1]*1000,translation[2]*1000)
        fun camera(world:ArVolume.V):ArVolume.V {val p=rotate(world-ArVolume.V(translation[0]*1000,translation[1]*1000,translation[2]*1000),true);return ArVolume.V(p.x,-p.y,-p.z)}
    }
    class Mapping(val source:DoubleArray,val target:DoubleArray) {
        init {require(source.size==8 && target.size==8 && (source+target).all {it.isFinite()});require(abs(det(source))>1e-8 && abs(det(target))>1e-8);val fourth=image(source[6],source[7]);require(abs(fourth.first-target[6])<1e-4 && abs(fourth.second-target[7])<1e-4)}
        private fun det(v:DoubleArray)=(v[2]-v[0])*(v[5]-v[1])-(v[3]-v[1])*(v[4]-v[0])
        private fun transform(x:Double,y:Double,from:DoubleArray,to:DoubleArray):Pair<Double,Double> {
            val a=((x-from[0])*(from[5]-from[1])-(y-from[1])*(from[4]-from[0]))/det(from)
            val b=((from[2]-from[0])*(y-from[1])-(from[3]-from[1])*(x-from[0]))/det(from)
            return Pair(to[0]+a*(to[2]-to[0])+b*(to[4]-to[0]),to[1]+a*(to[3]-to[1])+b*(to[5]-to[1]))
        }
        fun image(x:Double,y:Double)=transform(x,y,source,target)
        fun depth(x:Double,y:Double)=transform(x,y,target,source)
    }
    data class Observation(val id:String,val frame:ArVolume.Frame,val pose:Pose)
    data class Result(val frame:ArVolume.Frame,val sourceIds:List<String>,val rejectedConflictPixels:Int)
    fun combine(reference:Observation,sources:List<Observation>):Result {
        require(sources.size<=10)
        val ref=reference.frame;val map=Mapping(ref.source,ref.target)
        val samples=Array(ref.width*ref.height) {mutableListOf<Pair<Double,Int>>()}
        val ids=mutableListOf<String>();val seen=mutableSetOf<Long>()
        for(source in sources) {
            val f=source.frame
            if(ArTiming.reasons(f).isNotEmpty() || !seen.add(f.depthNs))continue
            require(f.width.toLong()*f.height<=1000000 && f.depth.size==f.width*f.height && f.confidence.size==f.depth.size)
            val mapping=Mapping(f.source,f.target);val perFrame=mutableMapOf<Int,Pair<Double,Int>>()
            for(y in 0 until f.height)for(x in 0 until f.width) {
                val i=y*f.width+x;val z=f.depth[i].toDouble();if(z<=0)continue // confidence filter removed (demo)
                val uv=mapping.image((x+.5)/f.width,(y+.5)/f.height)
                if(uv.first !in 0.0..1.0 || uv.second !in 0.0..1.0)continue
                val point=ArVolume.V((uv.first*f.imageWidth-f.cx)/f.fx*z,(uv.second*f.imageHeight-f.cy)/f.fy*z,z)
                val p=reference.pose.camera(source.pose.world(point));if(p.z<=0 || p.z>65535)continue
                val cpuX=(p.x/p.z*ref.fx+ref.cx)/ref.imageWidth;val cpuY=(p.y/p.z*ref.fy+ref.cy)/ref.imageHeight
                val uvDepth=map.depth(cpuX,cpuY);val xx=floor(uvDepth.first*ref.width).toInt();val yy=floor(uvDepth.second*ref.height).toInt()
                if(xx !in 0 until ref.width || yy !in 0 until ref.height)continue
                val key=yy*ref.width+xx;val old=perFrame[key]
                if(old==null || p.z<old.first)perFrame[key]=Pair(p.z,f.confidence[i])
            }
            for((key,value) in perFrame)samples[key].add(value)
            ids.add(source.id)
        }
        val depth=IntArray(samples.size);val confidence=IntArray(samples.size);var conflicts=0
        for(i in samples.indices) {
            val values=samples[i];if(values.isEmpty())continue
            if(values.maxOf {it.first}-values.minOf {it.first}>10) {conflicts++;continue}
            val sorted=values.map {it.first}.sorted();val middle=if(sorted.size%2==1)sorted[sorted.size/2] else (sorted[sorted.size/2-1]+sorted[sorted.size/2])/2
            depth[i]=middle.roundToInt();confidence[i]=values.minOf {it.second}
        }
        return Result(ref.copy(depth=depth,confidence=confidence),ids,conflicts)
    }
}

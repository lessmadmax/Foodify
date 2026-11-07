package com.example.foodify.ar

import kotlin.math.*
import java.util.Random

/** Experimental observed-surface integration. Units: mm, mm², mL. No inferred missing surfaces. */
object ArVolume {
    const val ALGORITHM_VERSION = 3
    data class V(val x:Double,val y:Double,val z:Double) {
        operator fun plus(o:V)=V(x+o.x,y+o.y,z+o.z)
        operator fun minus(o:V)=V(x-o.x,y-o.y,z-o.z)
        operator fun times(v:Double)=V(x*v,y*v,z*v)
        fun dot(o:V)=x*o.x+y*o.y+z*o.z
        fun cross(o:V)=V(y*o.z-z*o.y,z*o.x-x*o.z,x*o.y-y*o.x)
        fun length()=sqrt(dot(this))
    }
    data class Frame(val width:Int,val height:Int,val depth:IntArray,val confidence:IntArray,
        val imageWidth:Int,val imageHeight:Int,val fx:Double,val fy:Double,val cx:Double,val cy:Double,
        val source:DoubleArray,val target:DoubleArray,val tracking:Boolean,
        val frameNs:Long,val depthNs:Long,val confidenceNs:Long,val imageNs:Long,val sameFramePair:Boolean=false)
    data class Plane(val normal:V,val offset:Double,val rmse:Double,val inliers:Double)
    private fun fail(message:String):Nothing=throw IllegalArgumentException(message)
    internal fun mask(x:Double,y:Double,polygons:List<List<Pair<Double,Double>>>):Boolean = polygons.any { polygon ->
        var inside=false; var previous=polygon.last()
        for(current in polygon) {
            if((previous.second>y)!=(current.second>y) && x<(current.first-previous.first)*(y-previous.second)/(current.second-previous.second)+previous.first) inside=!inside
            previous=current
        };inside
    }
    private fun validate(polygons:List<List<Pair<Double,Double>>>) {
        require(polygons.size in 1..30) { "영역을 한 개 이상 지정해 주세요." }
        for(p in polygons) require(p.size in 3..200 && p.all { it.first.isFinite() && it.second.isFinite() && it.first in 0.0..1.0 && it.second in 0.0..1.0 }) { "영역 좌표를 확인해 주세요." }
    }
    fun fitPlane(points:List<V>):Plane {
        if(points.size<3) fail("접시 기준면의 깊이 점이 3개 이상 필요합니다.")
        val selectedPoints=if(points.size>20000) List(20000) { points[it*(points.size-1)/19999] } else points
        val random=Random(20261028); var best=emptyList<V>()
        repeat(200) {
            val a=selectedPoints[random.nextInt(selectedPoints.size)]
            val b=selectedPoints[random.nextInt(selectedPoints.size)]
            val c=selectedPoints[random.nextInt(selectedPoints.size)]
            var n=(b-a).cross(c-a);val length=n.length()
            if(length>1e-8) {
                n=n*(1/length); val inliers=selectedPoints.filter { abs((it-a).dot(n))<=5 }
                if(inliers.size>best.size) best=inliers
            }
        }
        if(best.size<3) fail("접시 기준면이 불안정합니다. 평평한 부분을 넓게 지정해 주세요.")
        val center=best.fold(V(0.0,0.0,0.0)) { a,b->a+b }*(1.0/best.size)
        val matrix=Array(3) { DoubleArray(3) };val vectors=Array(3) { i->DoubleArray(3) { j->if(i==j) 1.0 else 0.0 } }
        for(p in best) { val q=p-center;val a=doubleArrayOf(q.x,q.y,q.z);for(i in 0..2)for(j in 0..2)matrix[i][j]+=a[i]*a[j]/best.size }
        // Symmetric 3x3 Jacobi eigen decomposition for orthogonal plane refinement.
        repeat(40) {
            var a=0;var b=1
            for(i in 0..2)for(j in i+1..2)if(abs(matrix[i][j])>abs(matrix[a][b])) {a=i;b=j}
            if(abs(matrix[a][b])>1e-10) {
                val angle=.5*atan2(2*matrix[a][b],matrix[b][b]-matrix[a][a]);val c=cos(angle);val s=sin(angle)
                val aa=matrix[a][a];val bb=matrix[b][b];val ab=matrix[a][b]
                for(k in 0..2)if(k!=a && k!=b) { val ka=matrix[k][a];val kb=matrix[k][b];matrix[k][a]=c*ka-s*kb;matrix[a][k]=matrix[k][a];matrix[k][b]=s*ka+c*kb;matrix[b][k]=matrix[k][b] }
                matrix[a][a]=c*c*aa-2*s*c*ab+s*s*bb;matrix[b][b]=s*s*aa+2*s*c*ab+c*c*bb;matrix[a][b]=0.0;matrix[b][a]=0.0
                for(k in 0..2) {val ka=vectors[k][a];val kb=vectors[k][b];vectors[k][a]=c*ka-s*kb;vectors[k][b]=s*ka+c*kb}
            }
        }
        val order=(0..2).sortedBy { matrix[it][it] }
        if(sqrt(max(0.0,matrix[order[1]][order[1]]))<5) fail("기준면이 한 줄에 몰려 있습니다. 넓게 지정해 주세요.")
        val k=order[0];var n=V(vectors[0][k],vectors[1][k],vectors[2][k]);var d=-center.dot(n)
        if(d<0) {n=n*(-1.0);d=-d}
        val rmse=sqrt(best.sumOf { (it.dot(n)+d).pow(2) }/best.size)
        return Plane(n,d,rmse,best.size.toDouble()/selectedPoints.size)
    }
    /** Auto plate band: outside the food, 1%–4% of the image width from its edge. */
    const val AUTO_BAND_INNER=0.01
    const val AUTO_BAND_OUTER=0.04
    internal fun autoBand(width:Int,height:Int,food:BooleanArray,px:DoubleArray,py:DoubleArray,inside:BooleanArray,inner:Double,outer:Double):BooleanArray {
        val n=width*height;val nearest=IntArray(n) {-1};val dist=DoubleArray(n) {Double.MAX_VALUE}
        val queue=ArrayDeque<Int>()
        for(i in 0 until n) if(food[i]) {nearest[i]=i;dist[i]=0.0;queue.add(i)}
        // Wavefront propagation of the nearest food pixel (approximate Euclidean distance in image pixels).
        while(queue.isNotEmpty()) {
            val p=queue.removeFirst();val x=p%width;val y=p/width;val s=nearest[p]
            for(dy in -1..1)for(dx in -1..1) {
                val nx=x+dx;val ny=y+dy
                if((dx==0 && dy==0) || nx !in 0 until width || ny !in 0 until height) continue
                val q=ny*width+nx;if(food[q]) continue
                val d=hypot(px[q]-px[s],py[q]-py[s])
                if(d<dist[q] && d<=outer) {dist[q]=d;nearest[q]=s;queue.add(q)}
            }
        }
        return BooleanArray(n) {!food[it] && inside[it] && dist[it]>inner && dist[it]<=outer}
    }
    /** Depth-grid normalized coordinate → CPU-image normalized coordinate (validated affine map). */
    private fun mapper(f:Frame):(Double,Double)->Pair<Double,Double> {
        val count=f.width*f.height
        require(f.width>=2 && f.height>=2 && count<=1000000 && f.depth.size==count && f.confidence.size==count)
        require(f.fx.isFinite() && f.fy.isFinite() && f.fx>0 && f.fy>0 && f.cx.isFinite() && f.cy.isFinite() && f.imageWidth>0 && f.imageHeight>0)
        require(f.source.size==8 && f.target.size==8 && (f.source+f.target).all { it.isFinite() })
        val sx=f.source[2]-f.source[0];val sy=f.source[3]-f.source[1];val tx=f.source[4]-f.source[0];val ty=f.source[5]-f.source[1];val determinant=sx*ty-sy*tx
        require(abs(determinant)>1e-8)
        fun uv(x:Double,y:Double):Pair<Double,Double> {
            val dx=x-f.source[0];val dy=y-f.source[1];val a=(dx*ty-dy*tx)/determinant;val b=(sx*dy-sy*dx)/determinant
            return Pair(f.target[0]+a*(f.target[2]-f.target[0])+b*(f.target[4]-f.target[0]),f.target[1]+a*(f.target[3]-f.target[1])+b*(f.target[5]-f.target[1]))
        }
        val fourth=uv(f.source[6],f.source[7]);require(abs(fourth.first-f.target[6])<=1e-4 && abs(fourth.second-f.target[7])<=1e-4)
        val u0=uv(0.0,0.0);val ux=uv(1.0,0.0);val uy=uv(0.0,1.0)
        require(abs((ux.first-u0.first)*(uy.second-u0.second)-(ux.second-u0.second)*(uy.first-u0.first))>1e-8)
        return ::uv
    }
    /** Food height threshold above the plate plane for the AI-box refinement. */
    const val BOX_FOOD_MIN_HEIGHT_MM=5.0
    /**
     * AI box → food outline: plate plane from the band just outside the box, then the largest connected
     * group of in-box depth points at least 5 mm above it. Returns its convex hull in image coordinates,
     * or null when depth cannot separate the food (the caller then falls back to the box itself).
     */
    fun foodFromBox(f:Frame,box:DoubleArray):Map<String,Any?> {
        require(box.size==4 && box.all { it.isFinite() } && box[2]>box[0] && box[3]>box[1])
        val count=f.width*f.height;val uv=mapper(f)
        val px=DoubleArray(count);val py=DoubleArray(count);val ux=DoubleArray(count);val uy=DoubleArray(count)
        val inside=BooleanArray(count);val inBox=BooleanArray(count);val valid=BooleanArray(count);val points=Array(count) { V(0.0,0.0,0.0) }
        for(y in 0 until f.height)for(x in 0 until f.width) {
            val i=y*f.width+x;val u=uv((x+.5)/f.width,(y+.5)/f.height);val z=f.depth[i].toDouble()
            ux[i]=u.first;uy[i]=u.second;px[i]=u.first*f.imageWidth;py[i]=u.second*f.imageHeight
            inside[i]=u.first in 0.0..1.0 && u.second in 0.0..1.0
            inBox[i]=u.first in box[0]..box[2] && u.second in box[1]..box[3]
            valid[i]=z>0 && inside[i]
            points[i]=V((u.first*f.imageWidth-f.cx)/f.fx*z,(u.second*f.imageHeight-f.cy)/f.fy*z,z)
        }
        val fallback=listOf(listOf(Pair(box[0],box[1]),Pair(box[2],box[1]),Pair(box[2],box[3]),Pair(box[0],box[3])))
        fun boxOnly(reason:String)=mapOf("source" to "ai_box","reason" to reason,"foodPolygons" to fallback)
        if(!inBox.any { it }) return boxOnly("사진 안에서 AI 영역을 찾지 못했습니다.")
        val ring=autoBand(f.width,f.height,inBox,px,py,inside,AUTO_BAND_INNER*f.imageWidth,AUTO_BAND_OUTER*f.imageWidth)
        val plane=try { fitPlane(ring.indices.filter { ring[it] && valid[it] }.map { points[it] }) } catch(e:IllegalArgumentException) { return boxOnly(e.message ?: "기준면 확인 필요") }
        val raised=BooleanArray(count) { inBox[it] && valid[it] && points[it].dot(plane.normal)+plane.offset in BOX_FOOD_MIN_HEIGHT_MM..300.0 }
        // Largest 8-connected group of raised points.
        val label=IntArray(count) {-1};var best=emptyList<Int>()
        for(start in 0 until count) if(raised[start] && label[start]<0) {
            val group=ArrayList<Int>();val queue=ArrayDeque<Int>();queue.add(start);label[start]=start
            while(queue.isNotEmpty()) {
                val p=queue.removeFirst();group.add(p);val x=p%f.width;val y=p/f.width
                for(dy in -1..1)for(dx in -1..1) {
                    val nx=x+dx;val ny=y+dy
                    if(nx !in 0 until f.width || ny !in 0 until f.height) continue
                    val q=ny*f.width+nx;if(raised[q] && label[q]<0) {label[q]=start;queue.add(q)}
                }
            }
            if(group.size>best.size) best=group
        }
        if(best.size<10) return boxOnly("상자 안에서 접시보다 ${BOX_FOOD_MIN_HEIGHT_MM.toInt()}mm 이상 솟은 부분을 찾지 못했습니다.")
        val hull=convexHull(best.map { Pair(ux[it],uy[it]) })
        if(hull.size<3) return boxOnly("음식 윤곽을 만들지 못했습니다.")
        return mapOf("source" to "ai_box_depth","foodPolygons" to listOf(hull),"foodPixelCount" to best.size,"basePlaneRmseMm" to plane.rmse)
    }
    internal fun convexHull(input:List<Pair<Double,Double>>):List<Pair<Double,Double>> {
        val p=input.distinct().sortedWith(compareBy({ it.first },{ it.second }))
        if(p.size<3) return p
        fun cross(o:Pair<Double,Double>,a:Pair<Double,Double>,b:Pair<Double,Double>)=(a.first-o.first)*(b.second-o.second)-(a.second-o.second)*(b.first-o.first)
        val lower=ArrayList<Pair<Double,Double>>();for(q in p) {while(lower.size>=2 && cross(lower[lower.size-2],lower.last(),q)<=0) lower.removeAt(lower.size-1);lower.add(q)}
        val upper=ArrayList<Pair<Double,Double>>();for(q in p.reversed()) {while(upper.size>=2 && cross(upper[upper.size-2],upper.last(),q)<=0) upper.removeAt(upper.size-1);upper.add(q)}
        val hull=lower.dropLast(1)+upper.dropLast(1)
        // Keep within the polygon limit used by validate().
        return if(hull.size<=200) hull else List(200) { hull[it*hull.size/200] }
    }
    fun estimate(f:Frame,food:List<List<Pair<Double,Double>>>,base:List<List<Pair<Double,Double>>>):Map<String,Any?> {
        // Empty base = automatic plate band around the food (no manual plate region).
        val auto=base.isEmpty()
        validate(food);if(!auto)validate(base)
        val count=f.width*f.height;val uv=mapper(f)
        val points=Array(count) { V(0.0,0.0,0.0) };val fm=BooleanArray(count);val bm=BooleanArray(count);val valid=BooleanArray(count);var excludedFoodPixels=0
        val px=DoubleArray(count);val py=DoubleArray(count);val inside=BooleanArray(count)
        for(y in 0 until f.height)for(x in 0 until f.width) {
            val i=y*f.width+x;val u=uv((x+.5)/f.width,(y+.5)/f.height);val z=f.depth[i].toDouble()
            fm[i]=mask(u.first,u.second,food)
            val selectedBase=!auto && mask(u.first,u.second,base)
            px[i]=u.first*f.imageWidth;py[i]=u.second*f.imageHeight;inside[i]=u.first in 0.0..1.0 && u.second in 0.0..1.0
            if(selectedBase && fm[i]) excludedFoodPixels++
            bm[i]=selectedBase && !fm[i]
            // Depth-confidence filter removed (demo): any non-zero depth counts.
            valid[i]=z>0 && u.first in 0.0..1.0 && u.second in 0.0..1.0
            points[i]=V((u.first*f.imageWidth-f.cx)/f.fx*z,(u.second*f.imageHeight-f.cy)/f.fy*z,z)
        }
        require(fm.any { it }) { "깊이 좌표에서 음식 영역을 찾을 수 없습니다." }
        if(auto) autoBand(f.width,f.height,fm,px,py,inside,AUTO_BAND_INNER*f.imageWidth,AUTO_BAND_OUTER*f.imageWidth).copyInto(bm)
        val coverage=fm.indices.count { fm[it] && valid[it] }.toDouble()/fm.count { it }
        val common=linkedMapOf<String,Any?>("schemaVersion" to 1,"method" to "registered_raw_depth_mesh_v3","algorithmVersion" to ALGORITHM_VERSION,"baseSelectionPolicy" to if(auto)"auto_band_1_to_4pct_of_image_width" else "selected_plate_minus_food","excludedFoodPixelCount" to excludedFoodPixels,"baseCandidatePixelCount" to bm.count {it},"validBasePixelCount" to bm.indices.count {bm[it] && valid[it]},"volumeValidated" to false,"validFoodPixelFraction" to coverage,"rawSourceAgeMs" to (f.frameNs.toDouble()-f.depthNs)/1e6,"sameFramePair" to f.sameFramePair)
        val reasons=mutableListOf<String>()
        reasons.addAll(ArTiming.reasons(f))
        if(!bm.any {it}) reasons.add("음식을 제외한 접시 표면이 없습니다. 음식 주변의 접시 영역을 넓게 지정해 주세요.")
        val plane=try { fitPlane(points.indices.filter { bm[it] && valid[it] }.map { points[it] }) } catch(e:IllegalArgumentException) {
            common.putAll(mapOf("status" to "REVIEW_REQUIRED","holdReasons" to (reasons+listOf(e.message ?: "기준면 확인 필요")),"observedVolumeMl" to null,"volumeMl" to null,"estimatedGrams" to null));return common
        }
        val heights=DoubleArray(count) { points[it].dot(plane.normal)+plane.offset }
        val tilt=Math.toDegrees(acos(abs(plane.normal.z).coerceIn(0.0,1.0)))
        if(tilt>30) reasons.add("상단 촬영 각도 30도 초과")
        if(plane.rmse>3) reasons.add("접시 평면 잔차 3mm 초과")
        val goodFood=fm.indices.filter { fm[it] && valid[it] }
        if(goodFood.any { heights[it]>300 }) reasons.add("음식 높이 300mm 초과")
        if(goodFood.isEmpty() || goodFood.count { heights[it]< -5 }.toDouble()/goodFood.size>.05) reasons.add("기준면 아래 음식 점 5% 초과")
        var volume=0.0;var area=0.0;var total=0;var accepted=0
        fun triangle(a:Int,b:Int,c:Int) {
            val ids=intArrayOf(a,b,c);if(!ids.all { fm[it] })return;total++
            if(!ids.all { valid[it] && heights[it]>= -5 && heights[it]<=300 } || ids.maxOf { f.depth[it] }-ids.minOf { f.depth[it] }>30)return
            val surface=abs((points[b]-points[a]).cross(points[c]-points[a]).dot(plane.normal))/2
            area+=surface;volume+=surface*ids.sumOf { max(heights[it],0.0) }/3;accepted++
        }
        for(y in 0 until f.height-1)for(x in 0 until f.width-1) {val i=y*f.width+x;triangle(i,i+1,i+f.width);triangle(i+1,i+f.width+1,i+f.width)}
        val mesh=if(total>0)accepted.toDouble()/total else 0.0
        if(mesh<.8) reasons.add("부피 적분 가능 삼각형 비율 80% 미만")
        if(area<=0 || volume<=0) reasons.add("양의 음식 부피 미확보")
        common.putAll(mapOf("status" to if(reasons.isEmpty())"EXPERIMENT_ESTIMATE" else "REVIEW_REQUIRED","holdReasons" to reasons,
            "observedVolumeMl" to volume/1000,"volumeMl" to if(reasons.isEmpty())volume/1000 else null,"projectedObservedAreaCm2" to area/100,
            "meshCoverage" to mesh,"basePlaneRmseMm" to plane.rmse,"basePlaneInlierFraction" to plane.inliers,
            "basePlaneNormal" to listOf(plane.normal.x,plane.normal.y,plane.normal.z),"basePlaneOffsetMm" to plane.offset,"cameraTiltDegrees" to tilt,"estimatedGrams" to null))
        return common
    }
}

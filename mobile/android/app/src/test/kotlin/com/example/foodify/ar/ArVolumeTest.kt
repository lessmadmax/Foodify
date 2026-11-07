package com.example.foodify.ar

import org.junit.Assert.*
import org.junit.Test

class ArVolumeTest {
    private fun rectangle(x1:Double,y1:Double,x2:Double,y2:Double)=listOf(Pair(x1,y1),Pair(x2,y1),Pair(x2,y2),Pair(x1,y2))
    private val food=listOf(rectangle(.375,.375,.625,.625))
    private val base=listOf(rectangle(.1,.1,.3,.9),rectangle(.7,.1,.9,.9))
    private fun frame():ArVolume.Frame {
        val depth=IntArray(6400) {i->if(i%80 in 30..49 && i/80 in 30..49)450 else 500}
        val corners=doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0)
        return ArVolume.Frame(80,80,depth,IntArray(6400){255},80,80,100.0,100.0,40.0,40.0,corners,corners,true,1000000000,1000000000,1000000000,1000000000)
    }
    @Test fun knownBlockVolumeAndNoScaleDependency() {
        val r=ArVolume.estimate(frame(),food,base)
        assertEquals("EXPERIMENT_ESTIMATE",r["status"])
        assertEquals(365.5125,r["volumeMl"] as Double,1e-5)
        assertEquals(false,r["volumeValidated"])
        assertNull(r["estimatedGrams"])
    }
    @Test fun mismatchedDepthTimestampHoldsObservedValue() {
        val r=ArVolume.estimate(frame().copy(confidenceNs=999999999),food,base)
        assertNull(r["volumeMl"])
        assertNotNull(r["observedVolumeMl"])
        assertTrue((r["holdReasons"] as List<*>).contains("깊이·신뢰도의 동일 프레임 획득 근거 확인 필요"))
    }
    @Test fun missingDepthHoldsVolume() {
        val r=ArVolume.estimate(frame().copy(depth=IntArray(6400)),food,base)
        assertNull(r["volumeMl"])
        assertEquals("REVIEW_REQUIRED",r["status"])
    }
    @Test fun autoBandFindsPlateWithoutManualRegion() {
        // Plate (500mm) only near the food; farther away a lower table (520mm) must be ignored.
        val f=frame();val depth=f.depth.copyOf()
        for(i in depth.indices) { val x=i%80;val y=i/80; if(x !in 24..55 || y !in 24..55) depth[i]=520 }
        val r=ArVolume.estimate(f.copy(depth=depth),food,emptyList())
        assertEquals("EXPERIMENT_ESTIMATE",r["status"])
        assertEquals(365.5125,r["volumeMl"] as Double,1e-5)
        assertEquals("auto_band_1_to_4pct_of_image_width",r["baseSelectionPolicy"])
        assertEquals(500.0,r["basePlaneOffsetMm"] as Double,1e-5)
    }
    @Test fun autoBandSkipsFoodEdgeAndStaysWithinOuterDistance() {
        val w=20;val food=BooleanArray(w*w) {it%w in 8..11 && it/w in 8..11}
        val px=DoubleArray(w*w) {(it%w).toDouble()};val py=DoubleArray(w*w) {(it/w).toDouble()}
        val band=ArVolume.autoBand(w,w,food,px,py,BooleanArray(w*w){true},1.5,3.0)
        for(i in band.indices) if(band[i]) {
            assertFalse(food[i])
            val d=(0 until w*w).filter {food[it]}.minOf {Math.hypot(px[i]-px[it],py[i]-py[it])}
            assertTrue(d>1.5 && d<=3.0)
        }
        assertTrue(band[10*w+5]);assertFalse(band[10*w+7]);assertFalse(band[10*w+3])
    }
    @Test fun aiBoxIsRefinedToRaisedFoodWithDepth() {
        val r=ArVolume.foodFromBox(frame(),doubleArrayOf(.3,.3,.7,.7))
        assertEquals("ai_box_depth",r["source"])
        assertEquals(400,r["foodPixelCount"])
        @Suppress("UNCHECKED_CAST") val polygons=r["foodPolygons"] as List<List<Pair<Double,Double>>>
        val xs=polygons[0].map { it.first }
        assertEquals(30.5/80,xs.min(),1e-9);assertEquals(49.5/80,xs.max(),1e-9)
        val v=ArVolume.estimate(frame(),polygons,emptyList())
        assertTrue((v["observedVolumeMl"] as Double)>200)
    }
    @Test fun flatBoxFallsBackToAiRectangle() {
        val flat=frame().copy(depth=IntArray(6400) {500})
        val r=ArVolume.foodFromBox(flat,doubleArrayOf(.3,.3,.7,.7))
        assertEquals("ai_box",r["source"])
        assertNotNull(r["reason"])
    }
    @Test fun convexHullDropsInteriorPoints() {
        val hull=ArVolume.convexHull(listOf(Pair(0.0,0.0),Pair(1.0,0.0),Pair(1.0,1.0),Pair(0.0,1.0),Pair(.5,.5)))
        assertEquals(4,hull.size);assertFalse(hull.contains(Pair(.5,.5)))
    }
    @Test fun wholePlateExcludesFoodAndPreservesVolume() {
        val wholePlate=listOf(rectangle(.1,.1,.9,.9))
        val r=ArVolume.estimate(frame(),food,wholePlate)
        assertEquals("EXPERIMENT_ESTIMATE",r["status"])
        assertEquals(365.5125,r["volumeMl"] as Double,1e-5)
        assertEquals(400,r["excludedFoodPixelCount"])
        assertEquals(3696,r["baseCandidatePixelCount"])
        assertEquals(500.0,r["basePlaneOffsetMm"] as Double,1e-5)
        assertEquals(3,r["algorithmVersion"])
    }
    @Test fun onlyFoodSelectedAsPlateNeedsMorePlateSurface() {
        val r=ArVolume.estimate(frame(),food,food)
        assertNull(r["volumeMl"])
        assertEquals(0,r["baseCandidatePixelCount"])
        assertTrue((r["holdReasons"] as List<*>).any {it.toString().contains("음식을 제외한 접시 표면")})
    }
    @Test fun partialOverlapMatchesExplicitPlateRemainder() {
        val partial=listOf(rectangle(.1,.1,.5,.9))
        val explicit=listOf(rectangle(.1,.1,.375,.9),rectangle(.375,.1,.5,.375),rectangle(.375,.625,.5,.9))
        val a=ArVolume.estimate(frame(),food,partial);val b=ArVolume.estimate(frame(),food,explicit)
        assertEquals(b["volumeMl"] as Double,a["volumeMl"] as Double,1e-5)
        assertEquals(b["baseCandidatePixelCount"],a["baseCandidatePixelCount"])
    }
    @Test fun tiltedPlaneOrthogonalFit() {
        val points=(0..15).flatMap {x->(0..15).map {y->ArVolume.V(x*10.0,y*10.0,500+x*2.0)}}
        val p=ArVolume.fitPlane(points)
        assertTrue(p.rmse<1e-7)
        assertTrue(points.all {kotlin.math.abs(it.dot(p.normal)+p.offset)<1e-6})
        assertEquals(1.0,p.normal.length(),1e-7)
    }
    @Test(expected=IllegalArgumentException::class) fun collinearBaseRejected() {ArVolume.fitPlane((0..50).map {ArVolume.V(it*10.0,0.0,500.0)})}
}

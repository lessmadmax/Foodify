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
    @Test fun scaleUsesPlateBandDistanceForTopViewArea() {
        // Food 25%×25% of an 80px image at 500mm with fx=100: 5mm per pixel, 400px → 100cm².
        val r=ArVolume.scale(frame(),food,emptyList())
        assertEquals(500.0,r["distanceMm"] as Double,1e-9)
        assertEquals("auto_band",r["distanceSource"])
        assertEquals(100.0,r["areaCm2"] as Double,1e-6)
        assertEquals(5.0,r["mmPerPixel"] as Double,1e-9)
        assertEquals(40.0,r["photoWidthCm"] as Double,1e-9)
        assertEquals("base_region",ArVolume.scale(frame(),food,base)["distanceSource"])
        assertThrows(IllegalArgumentException::class.java) { ArVolume.scale(frame().copy(depth=IntArray(6400)),food,emptyList()) }
    }
    @Test fun scaleIgnoresLowConfidencePlateNoise() {
        // Every other plate pixel is far, low-confidence noise (white desk): the distance stays on confident 500 mm.
        val f=frame();val depth=f.depth.copyOf();val confidence=f.confidence.copyOf()
        for(i in depth.indices) if(depth[i]==500 && i%2==0) {depth[i]=4000;confidence[i]=3}
        assertEquals(500.0,ArVolume.scale(f.copy(depth=depth,confidence=confidence),food,base)["distanceMm"] as Double,1e-9)
    }
    @Test fun scaleRefusesWhenPlateDepthAndFeaturePointsAreMissing() {
        val f=frame()
        val e=assertThrows(IllegalArgumentException::class.java) { ArVolume.scale(f.copy(confidence=IntArray(6400){3}),food,base) }
        assertTrue(e.message!!.contains("특징점"))
    }
    /** Camera-space point (mm) that projects to normalized image (u,v) at depth z for the test frame (fx=100, cx=40, 80px). */
    private fun pointAt(u:Double,v:Double,z:Double)=ArVolume.V((u*80-40)/100*z,(v*80-40)/100*z,z)
    @Test fun whitePlateFallsBackToFeaturePointsOnPlate() {
        // No confident plate depth (white bowl); feature points on the plate band at 300 mm, a few on the food top at 280 mm.
        val white=frame().copy(confidence=IntArray(6400){3})
        val points=(0 until 8).map { pointAt(.3,.3+it*.05,300.0) }+(0 until 3).map { pointAt(.5,.45+it*.05,280.0) }+
            (0 until 8).map { pointAt(.05,.1+it*.1,900.0) } // far from the food: ignored
        val r=ArVolume.scale(white,food,emptyList(),points)
        assertEquals("feature_points_plate",r["distanceSource"])
        assertEquals(300.0,r["distanceMm"] as Double,1e-9)
        assertEquals(8,r["plateSamples"])
        // Area follows the feature distance: 400 px × (300/100 mm)² = 36 cm².
        assertEquals(36.0,r["areaCm2"] as Double,1e-6)
    }
    @Test fun onlyFoodFeaturePointsGiveFoodTopDistance() {
        val white=frame().copy(confidence=IntArray(6400){3})
        val r=ArVolume.scale(white,food,base,(0 until 6).map { pointAt(.45+it*.02,.5,280.0) })
        assertEquals("feature_points_food",r["distanceSource"])
        assertEquals(280.0,r["distanceMm"] as Double,1e-9)
    }
    @Test fun confidentPlateDepthStillWinsOverFeaturePoints() {
        val r=ArVolume.scale(frame(),food,emptyList(),(0 until 8).map { pointAt(.2,.2+it*.08,300.0) })
        assertEquals("auto_band",r["distanceSource"])
        assertEquals(500.0,r["distanceMm"] as Double,1e-9)
    }
    @Test fun measuredHeightFromConfidentFoodDepth() {
        // Test frame: plate 500 mm, food block 450 mm -> 50 mm high over the whole outline.
        val r=ArVolume.scale(frame(),food,emptyList())
        assertEquals("DEPTH",r["measuredHeightSource"])
        assertEquals(5.0,r["measuredHeightCm"] as Double,1e-9)
        assertEquals(400,r["measuredHeightSamples"])
    }
    @Test fun measuredHeightFallsBackToFoodFeaturePoints() {
        // Plate depth is confident, food depth is not; six feature points sit 20 mm above the plate.
        val f=frame();val confidence=IntArray(6400) {i->if(f.depth[i]==450)3 else 255}
        val points=(0 until 6).map { pointAt(.45+it*.02,.5,480.0) }
        val r=ArVolume.scale(f.copy(confidence=confidence),food,emptyList(),points)
        assertEquals("FEATURE_POINTS",r["measuredHeightSource"])
        assertEquals(2.0,r["measuredHeightCm"] as Double,1e-9)
    }
    @Test fun noMeasuredHeightWithoutSamplesOrFromFoodTopDistance() {
        val f=frame();val confidence=IntArray(6400) {i->if(f.depth[i]==450)3 else 255}
        val r=ArVolume.scale(f.copy(confidence=confidence),food,emptyList())
        assertNull(r["measuredHeightCm"])
        assertEquals(0,r["measuredHeightSamples"])
        // Distance from food-top feature points: height cannot be measured.
        val white=f.copy(confidence=IntArray(6400){3})
        val top=ArVolume.scale(white,food,base,(0 until 6).map { pointAt(.45+it*.02,.5,280.0) })
        assertEquals("feature_points_food",top["distanceSource"])
        assertNull(top["measuredHeightCm"])
    }
    @Test fun flatFoodBelowOneMillimetreUsesAiHeight() {
        val flat=frame().copy(depth=IntArray(6400){500})
        assertNull(ArVolume.scale(flat,food,emptyList())["measuredHeightCm"])
    }
    @Test fun featurePointsBehindCameraOrOutsideImageAreIgnored() {
        val white=frame().copy(confidence=IntArray(6400){3})
        val bad=(0 until 8).map { ArVolume.V(0.0,0.0,-300.0) }+(0 until 8).map { pointAt(1.5,.5,300.0) }
        assertThrows(IllegalArgumentException::class.java) { ArVolume.scale(white,food,emptyList(),bad) }
    }
    @Test fun scaleRefusesImplausibleDistance() {
        val far=frame().copy(depth=IntArray(6400){2706})
        val e=assertThrows(IllegalArgumentException::class.java) { ArVolume.scale(far,food,base) }
        assertTrue(e.message!!.contains("270.6cm"))
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

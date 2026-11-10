package com.example.foodify.ar

import org.junit.Assert.*
import org.junit.Test

class SamGeometryTest {
    @Test fun resizeKeepsLongestSideAt1024() {
        assertEquals(Pair(1024,768),SamGeometry.resized(640,480))
        assertEquals(Pair(768,1024),SamGeometry.resized(480,640))
        assertEquals(1.6,SamGeometry.scale(640,480),1e-12)
    }
    @Test fun inputNormalizesChannelsAndZeroPads() {
        // One white and one black pixel in a 2x1 image.
        val x=SamGeometry.input(intArrayOf(0xFFFFFFFF.toInt(),0xFF000000.toInt()),2,1)
        val plane=1024*1024
        assertEquals((255-123.675f)/58.395f,x[0],1e-5f)
        assertEquals((255-116.28f)/57.12f,x[plane],1e-5f)
        assertEquals((255-103.53f)/57.375f,x[2*plane],1e-5f)
        assertEquals(-123.675f/58.395f,x[1],1e-5f)
        assertEquals(0f,x[2],0f)        // padding to the right
        assertEquals(0f,x[1024],0f)     // padding below
    }
    @Test fun boxCoordsAreInModelInputPixels() {
        val c=SamGeometry.boxCoords(doubleArrayOf(.25,.5,.75,1.0),640,480)
        assertArrayEquals(floatArrayOf(256f,384f,768f,768f),c,1e-4f)
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.boxCoords(doubleArrayOf(.5,.5,.4,.6),640,480) }
    }
    @Test fun scatteredPiecesBecomeSeparateOutlinesWithoutGaps() {
        val w=100;val h=100;val mask=BooleanArray(w*h)
        fun square(x0:Int,y0:Int,size:Int) { for(y in y0 until y0+size) for(x in x0 until x0+size) mask[y*w+x]=true }
        square(10,10,10);square(60,60,20);mask[50*w+5]=true // a 1-pixel crumb is dropped
        val polygons=SamGeometry.polygons(mask,w,h)
        assertEquals(2,polygons.size)
        fun area(p:List<Pair<Double,Double>>)=kotlin.math.abs(p.indices.sumOf { k -> val a=p[k];val b=p[(k+1)%p.size];a.first*b.second-b.first*a.second })/2
        // Largest first; areas equal the pixel counts (outline on pixel corners), gaps between pieces excluded.
        assertEquals(400.0/(w*h),area(polygons[0]),1e-9)
        assertEquals(100.0/(w*h),area(polygons[1]),1e-9)
        assertTrue(polygons.flatten().all { it.first in 0.0..1.0 && it.second in 0.0..1.0 })
    }
    @Test fun atMostThirtyPiecesLargestFirst() {
        val w=200;val h=200;val mask=BooleanArray(w*h)
        var n=0
        for(gy in 0 until 6) for(gx in 0 until 6) { val size=4+(n++%5); for(y in 0 until size) for(x in 0 until size) mask[(gy*30+y)*w+gx*30+x]=true }
        val polygons=SamGeometry.polygons(mask,w,h)
        assertEquals(SamGeometry.MAX_PIECES,polygons.size)
        assertTrue(polygons.all { it.size in 3..200 })
    }
    @Test fun tapPromptAddsPaddingPointWithoutBox() {
        val (coords,labels)=SamGeometry.prompt(listOf(Triple(.5,.25,1),Triple(.1,.9,0)),null,640,480)
        assertArrayEquals(floatArrayOf(512f,192f,102.4f,691.2f,0f,0f),coords,1e-3f)
        assertArrayEquals(floatArrayOf(1f,0f,-1f),labels,0f)
        val (boxCoords,boxLabels)=SamGeometry.prompt(listOf(Triple(.5,.5,1)),doubleArrayOf(.25,.5,.75,1.0),640,480)
        assertEquals(6,boxCoords.size)
        assertArrayEquals(floatArrayOf(1f,2f,3f),boxLabels,0f)
    }
    @Test fun tapPromptNeedsAFoodPointAndValidCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.prompt(emptyList(),null,640,480) }
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.prompt(listOf(Triple(.5,.5,0)),null,640,480) }
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.prompt(listOf(Triple(1.5,.5,1)),null,640,480) }
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.prompt(listOf(Triple(.5,.5,2)),null,640,480) }
        assertThrows(IllegalArgumentException::class.java) { SamGeometry.prompt(List(21) { Triple(.5,.5,1) },null,640,480) }
    }
    @Test fun emptyMaskGivesNoOutline() {
        assertTrue(SamGeometry.polygons(BooleanArray(64),8,8).isEmpty())
    }
}

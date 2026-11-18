package com.example.foodify.ar

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

class ArVesselTest {
    private val corners=doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0)
    private val rim=List(12) {i-> val a=2*PI*i/12;Pair(.5+.3*cos(a),.5+.3*sin(a))}
    private val food=listOf(listOf(Pair(.4,.4),Pair(.6,.4),Pair(.6,.6),Pair(.4,.6)))
    private val vessel=ArVessel.Detection("soup_bowl","high",rim)
    private fun frame(foodDepth:Int=310):ArVolume.Frame {
        val depths=IntArray(160*120) {i-> val u=(i%160+.5)/160;val v=(i/160+.5)/120
            if(ArVolume.mask(u,v,food))foodDepth else 300}
        return ArVolume.Frame(160,120,depths,IntArray(depths.size){255},640,480,600.0,600.0,320.0,240.0,
            corners,corners,true,1000000000,1000000000,1000000000,1000000000,true)
    }
    @Test fun rimAndDefaultDepthProduceBottomNotRimHeight() {
        val r=ArVessel.scale(frame(),food,vessel)
        assertEquals(330.0,r["distanceMm"] as Double,.01)
        assertEquals(2.0,r["measuredHeightCm"] as Double,.01)
        assertEquals(3.0,(r["vesselReference"] as Map<*,*>)["internalDepthCm"])
        val plate=ArVessel.scale(frame(),food,vessel.copy(type="flat_plate"))
        assertEquals(308.0,plate["distanceMm"] as Double,.01)
    }
    @Test fun missingRimDepthDoesNotUseTableAsBottom() {
        val f=frame();assertThrows(IllegalArgumentException::class.java) {
            ArVessel.scale(f.copy(confidence=IntArray(f.depth.size)),food,vessel)
        }
    }
    @Test fun missingFoodDepthDoesNotInventMeasuredHeight() {
        val r=ArVessel.scale(frame(0),food,vessel)
        assertNull(r["measuredHeightCm"])
        assertEquals(330.0,r["distanceMm"] as Double,.01)
    }
    @Test fun foodOutsideDetectedVesselIsRejected() {
        val wrong=listOf(listOf(Pair(.01,.01),Pair(.1,.01),Pair(.1,.1),Pair(.01,.1)))
        assertThrows(IllegalArgumentException::class.java) {ArVessel.scale(frame(),wrong,vessel)}
    }
    @Test fun lowConfidenceAndCrossedRimAreRejected() {
        assertFalse(ArVessel.eligible(vessel.copy(confidence="low")))
        assertThrows(IllegalArgumentException::class.java) {ArVessel.scale(frame(),food,vessel.copy(rim=rim.reversed().chunked(2).flatten().sortedBy {it.first}))}
    }
}

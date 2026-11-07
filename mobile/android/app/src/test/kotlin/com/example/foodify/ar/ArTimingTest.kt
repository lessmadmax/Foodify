package com.example.foodify.ar
import org.junit.Assert.*
import org.junit.Test
class ArTimingTest {
 private fun frame()=ArVolume.Frame(2,2,IntArray(4){500},IntArray(4){255},2,2,1.0,1.0,1.0,1.0,doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0),doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0),true,1000000000,900000000,1000000000,1000000000,true)
 @Test fun sameFrameRawReprojectionAccepted() {assertTrue(ArTiming.reasons(frame()).isEmpty())}
 @Test fun unknownPairRequiresEvidence() {assertTrue(ArTiming.reasons(frame().copy(sameFramePair=false)).isNotEmpty())}
 @Test fun staleConfidenceRejected() {assertTrue(ArTiming.reasons(frame().copy(confidenceNs=500000000)).isNotEmpty())}
 @Test fun oldSourceRejected() {assertTrue(ArTiming.reasons(frame().copy(frameNs=3000000000,imageNs=3000000000,confidenceNs=3000000000)).isNotEmpty())}
 @Test fun futureDepthRejected() {assertTrue(ArTiming.reasons(frame().copy(depthNs=1100000000)).isNotEmpty())}
}

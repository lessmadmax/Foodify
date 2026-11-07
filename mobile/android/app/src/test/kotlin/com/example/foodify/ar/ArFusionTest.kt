package com.example.foodify.ar
import org.junit.Assert.*
import org.junit.Test
class ArFusionTest {
 private val identity=ArFusion.Pose(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(0.0,0.0,0.0,1.0))
 private fun observation(id:String,stamp:Long=1000000000)=ArFusion.Observation(id,ArVolume.Frame(4,4,IntArray(16){500},IntArray(16){200},4,4,4.0,4.0,2.0,2.0,doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0),doubleArrayOf(0.0,0.0,1.0,0.0,0.0,1.0,1.0,1.0),true,stamp,stamp,stamp,stamp,true),identity)
 @Test fun identityPreservesDepthAndConfidence() {val a=observation("a");val r=ArFusion.combine(a,listOf(a));assertArrayEquals(a.frame.depth,r.frame.depth);assertArrayEquals(a.frame.confidence,r.frame.confidence)}
 @Test fun complementaryObservationsRecoverOnlyObservedCells() {
  val a=observation("a");val b=observation("b",1100000000)
  val x=a.copy(frame=a.frame.copy(confidence=IntArray(16){if(it%2==0)200 else 0}))
  val y=b.copy(frame=b.frame.copy(confidence=IntArray(16){if(it%2==1)180 else 0}))
  val r=ArFusion.combine(x,listOf(x,y));assertTrue(r.frame.depth.all {it==500});assertEquals(2,r.sourceIds.size)
 }
 @Test fun conflictingDepthIsHeldAndNoHolesInvented() {
  val a=observation("a");val b=observation("b",1100000000)
  val r=ArFusion.combine(a,listOf(a,b.copy(frame=b.frame.copy(depth=IntArray(16){550}))))
  assertEquals(16,r.rejectedConflictPixels);assertTrue(r.frame.confidence.all {it==0})
  val empty=ArFusion.combine(a,listOf(a.copy(frame=a.frame.copy(depth=IntArray(16)))))
  assertTrue(empty.frame.depth.all {it==0})
 }
 @Test fun cameraPoseUsesMetresAndOpenGlAxisConvention() {
  val p=ArFusion.Pose(doubleArrayOf(.1,.2,.3),doubleArrayOf(0.0,0.0,0.0,1.0));val original=ArVolume.V(20.0,30.0,500.0);val world=p.world(original)
  assertEquals(120.0,world.x,1e-8);assertEquals(170.0,world.y,1e-8);assertEquals(-200.0,world.z,1e-8)
  val back=p.camera(world);assertEquals(original.x,back.x,1e-8);assertEquals(original.y,back.y,1e-8);assertEquals(original.z,back.z,1e-8)
 }
 @Test fun translatedCameraReprojectsByOnePixel() {
  val a=observation("a");val b=observation("b",1100000000).copy(pose=ArFusion.Pose(doubleArrayOf(.125,0.0,0.0),doubleArrayOf(0.0,0.0,0.0,1.0)))
  val r=ArFusion.combine(a,listOf(b));assertTrue((0 until 4).all {r.frame.depth[it*4]==0});assertEquals(12,r.frame.depth.count {it==500})
 }
 @Test fun duplicateObservationTimestampHasOneVote() {val a=observation("a");assertEquals(1,ArFusion.combine(a,listOf(a,a.copy(id="b"))).sourceIds.size)}
}

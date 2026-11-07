package com.example.foodify.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ArGuidanceTest {
    private fun zAxisForTilt(degrees: Double): FloatArray {
        val r = Math.toRadians(degrees)
        // Looking straight down means camera -Z = world -Y, i.e. zAxis = (0, 1, 0).
        return floatArrayOf(sin(r).toFloat(), cos(r).toFloat(), 0f)
    }

    @Test fun straightDownIsZeroTilt() {
        assertEquals(0.0, ArGuidance.tiltDegrees(floatArrayOf(0f, 1f, 0f)), 1e-6)
    }

    @Test fun lookingAtHorizonIsNinetyDegrees() {
        assertEquals(90.0, ArGuidance.tiltDegrees(floatArrayOf(0f, 0f, 1f)), 1e-6)
    }

    @Test fun tiltAngleIsRecovered() {
        assertEquals(30.0, ArGuidance.tiltDegrees(zAxisForTilt(30.0)), 1e-4)
        assertEquals(8.0, ArGuidance.tiltDegrees(zAxisForTilt(8.0)), 1e-4)
    }

    @Test fun tiltThresholdIsTenDegrees() {
        assertTrue(ArGuidance.advise(10.0, 0.05, recording = false).tiltOk)
        val tilted = ArGuidance.advise(25.0, 0.05, recording = false)
        assertFalse(tilted.tiltOk)
        assertTrue(tilted.messages.single().contains("25°"))
    }

    @Test fun speedNeedsHalfWindow() {
        val window = ArGuidance.MotionWindow(600)
        window.add(0, floatArrayOf(0f, 0f, 0f))
        window.add(200, floatArrayOf(0.01f, 0f, 0f))
        assertNull(window.speed())
        window.add(400, floatArrayOf(0.02f, 0f, 0f))
        assertEquals(0.05, window.speed()!!, 1e-6)
    }

    @Test fun windowDropsOldSamples() {
        val window = ArGuidance.MotionWindow(600)
        window.add(0, floatArrayOf(0f, 0f, 0f))
        window.add(1000, floatArrayOf(1f, 0f, 0f))  // fast jump long ago
        for (t in 1100L..2000L step 100) window.add(t, floatArrayOf(1f, 0f, 0f))
        assertEquals(0.0, window.speed()!!, 1e-9)
    }

    @Test fun motionClassification() {
        assertEquals(ArGuidance.Motion.UNKNOWN, ArGuidance.motion(null))
        assertEquals(ArGuidance.Motion.STILL, ArGuidance.motion(0.005))
        assertEquals(ArGuidance.Motion.OK, ArGuidance.motion(0.05))
        assertEquals(ArGuidance.Motion.FAST, ArGuidance.motion(0.3))
    }

    @Test fun stillWarnsOnlyWhileRecording() {
        assertTrue(ArGuidance.advise(5.0, 0.0, recording = false).messages.isEmpty())
        assertEquals(1, ArGuidance.advise(5.0, 0.0, recording = true).messages.size)
        assertTrue(ArGuidance.advise(5.0, 0.05, recording = true).ok)
    }
}

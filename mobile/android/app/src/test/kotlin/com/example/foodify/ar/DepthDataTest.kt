package com.example.foodify.ar

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DepthDataTest {
    @Test fun packsPaddedRowsAndPixelStride() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 99, 3, 4, 99, 88, 88, 5, 6, 99, 7, 8))
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8), DepthData.pack(source,2,2,8,3,2))
        assertEquals(0, source.position())
    }
    @Test fun respectsBufferOffset() {
        val source = ByteBuffer.wrap(byteArrayOf(99,1,2,3,4))
        source.position(1)
        assertArrayEquals(byteArrayOf(1,2,3,4), DepthData.pack(source,2,1,4,2,2))
    }
    @Test fun decodesUnsignedLittleEndianWithout13BitMask() {
        assertEquals(65535, DepthData.millimetres(byteArrayOf(-1,-1),0))
        assertEquals(1000, DepthData.millimetres(byteArrayOf(-24,3),0))
    }
    @Test fun ignoresMissingAndLowConfidenceInMedian() {
        val s = DepthData.stats(byteArrayOf(0,0,-24,3,-48,7), byteArrayOf(-1,127,-1))
        assertEquals(2,s.valid)
        assertEquals(1,s.confident)
        assertEquals(2000,s.medianMm)
    }
    @Test fun noDepthIsUnknownNotZeroDistance() {
        assertNull(DepthData.stats(byteArrayOf(0,0),byteArrayOf(0)).medianMm)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsShortPlane() {
        DepthData.pack(ByteBuffer.allocate(1),2,2,4,2,2)
    }
}

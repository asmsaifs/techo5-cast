package dev.techo5.cast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JpegBudgetTest {
    @Test
    fun aFrameThatFitsIsEncodedOnce() {
        val asked = mutableListOf<Int>()
        val out = encodeWithin(1000, 80) { q -> asked += q; ByteArray(1000) }
        assertEquals(1000, out!!.size)
        assertEquals(listOf(80), asked)
    }

    @Test
    fun aLargeFrameIsTriedAtLowerQualitiesUntilItFits() {
        val asked = mutableListOf<Int>()
        val out = encodeWithin(1000, 95) { q -> asked += q; ByteArray(q * 20) }
        assertEquals(listOf(95, 80, 65, 50), asked)
        assertEquals(1000, out!!.size)
    }

    @Test
    fun aFrameThatNeverFitsIsNotSent() {
        val asked = mutableListOf<Int>()
        assertNull(encodeWithin(10, 80) { q -> asked += q; ByteArray(100) })
        assertEquals(listOf(80, 65, 50, 35, 30), asked)
    }
}

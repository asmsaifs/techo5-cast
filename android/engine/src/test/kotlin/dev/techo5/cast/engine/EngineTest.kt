package dev.techo5.cast.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun audioBorrowsTheVideoAnchor() {
        val t = Timeline()
        val e = t.epoch
        assertNull(t.clockFor(e, 1_000_000))
        t.anchor(mediaUs = 2_000_000, clockUs = 50_000_000)
        assertEquals(50_000_000L, t.clockFor(e, 2_000_000))
        assertEquals(50_500_000L, t.clockFor(e, 2_500_000))
        assertEquals(49_000_000L, t.clockFor(e, 1_000_000))
    }

    @Test
    fun aSeekMakesOldDataUnplaceable() {
        val t = Timeline()
        val old = t.epoch
        t.anchor(0, 1_000)
        t.newEpoch()
        assertNull(t.clockFor(old, 0))
        assertNull(t.clockFor(t.epoch, 0)) // and no anchor yet for the new one
        t.anchor(9_000_000, 5_000)
        assertNull(t.clockFor(old, 9_000_000))
        assertEquals(5_000L, t.clockFor(t.epoch, 9_000_000))
    }

    @Test
    fun pauseKeepsTheEpochButNotTheAnchor() {
        val t = Timeline()
        val e = t.epoch
        t.anchor(0, 1_000)
        t.invalidate()
        assertNull(t.clockFor(e, 0))
        t.anchor(500_000, 9_000_000)
        assertEquals(8_500_000L, t.clockFor(e, 0)) // queued audio from before the pause is placed again
    }
}

class ResamplerTest {
    private fun tone(from: Int, frames: Int, rate: Int, channels: Int): ByteArray {
        val b = ByteArray(frames * channels * 2)
        for (i in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 440.0 * (from + i) / rate) * 10000).toInt()
            for (c in 0 until channels) {
                b[(i * channels + c) * 2] = (v and 0xFF).toByte()
                b[(i * channels + c) * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return b
    }

    @Test
    fun outputLengthMatchesTheRatioAcrossCalls() {
        for ((rate, ch) in listOf(44100 to 2, 48000 to 2, 16000 to 1, 22050 to 1, 44100 to 6)) {
            val r = Resampler(rate, ch)
            var total = 0
            val call = rate / 50 // 20 ms
            repeat(50) { i -> total += r.process(tone(i * call, call, rate, ch), 0, call * ch * 2).size / 4 }
            // A second of input is a second of output, to within the input frame or two held back.
            assertEquals("$rate Hz $ch ch", 48000.0, total.toDouble(), 2.0 * 48000 / rate + 1)
        }
    }

    @Test
    fun aToneStaysContinuousAcrossCallBoundaries() {
        val r = Resampler(44100, 2)
        val all = r.process(tone(0, 441, 44100, 2), 0, 441 * 4) + r.process(tone(441, 441, 44100, 2), 0, 441 * 4)
        var worst = 0
        var prev = 0
        for (i in 0 until all.size / 4) {
            val v = (all[i * 4].toInt() and 0xFF) or (all[i * 4 + 1].toInt() shl 8)
            if (i > 0) worst = maxOf(worst, Math.abs(v - prev))
            prev = v
        }
        // 440 Hz at 48 kHz, amplitude 10000: the largest step is about 2*pi*440/48000*10000 = 576. A
        // glitch at the join (a repeated or lost frame) would be a lot bigger.
        assertTrue("step $worst", worst < 700)
    }
}

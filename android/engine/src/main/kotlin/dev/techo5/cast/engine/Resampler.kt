package dev.techo5.cast.engine

/**
 * Turns interleaved S16LE PCM of any rate and 1 to 8 channels into the device's only format: 48 kHz
 * stereo S16LE. Linear interpolation: the device's speaker is a small one, and the plan's point is a
 * cheap, predictable stage with no latency of its own (a Sonic-based one buffers).
 *
 * Stateful across [process] calls; [reset] on a flush.
 */
class Resampler(private val inRate: Int, private val inChannels: Int, private val outRate: Int = OUT_RATE) {
    private var phase = 1.0 // next output position, as an index into process()'s working arrays
    private var prevL = 0
    private var prevR = 0
    private var havePrev = false
    private val step = inRate.toDouble() / outRate

    fun reset() {
        phase = 1.0
        havePrev = false
    }

    /** Converts [len] bytes of input at [off]; returns the interleaved output. */
    fun process(input: ByteArray, off: Int, len: Int): ByteArray {
        val frames = len / (2 * inChannels)
        if (frames == 0) return ByteArray(0)
        // Index 0 is the last frame of the previous call (or a copy of the first, at the start);
        // indices 1..frames are this call's. An output at position `pos` is interpolated between
        // index floor(pos) and the next, so it needs pos + 1 <= frames.
        val l = IntArray(frames + 1)
        val r = IntArray(frames + 1)
        for (k in 0 until frames) frameToStereo(input, off, k, l, r, k + 1)
        if (havePrev) {
            l[0] = prevL; r[0] = prevR
        } else {
            l[0] = l[1]; r[0] = r[1]
        }
        val out = java.io.ByteArrayOutputStream((frames / step * 4).toInt() + 8)
        var pos = phase
        while (pos + 1 <= frames) {
            val base = pos.toInt()
            val f = pos - base
            writeS16(out, (l[base] + (l[base + 1] - l[base]) * f).toInt())
            writeS16(out, (r[base] + (r[base + 1] - r[base]) * f).toInt())
            pos += step
        }
        prevL = l[frames]; prevR = r[frames]; havePrev = true
        phase = pos - frames
        return out.toByteArray()
    }

    private fun writeS16(out: java.io.ByteArrayOutputStream, v: Int) {
        val c = v.coerceIn(-32768, 32767)
        out.write(c and 0xFF)
        out.write((c shr 8) and 0xFF)
    }

    private fun sample(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)

    /** Reads input frame [k] and stores its stereo mix at [l]/[r] index [dst]. */
    private fun frameToStereo(b: ByteArray, off: Int, k: Int, l: IntArray, r: IntArray, dst: Int) {
        val at = off + k * inChannels * 2
        when (inChannels) {
            1 -> { val s = sample(b, at); l[dst] = s; r[dst] = s }
            2 -> { l[dst] = sample(b, at); r[dst] = sample(b, at + 2) }
            6 -> {
                // Android order: FL FR FC LFE BL BR. Standard fold-down, centre and surrounds at -3 dB.
                val fl = sample(b, at); val fr = sample(b, at + 2); val fc = sample(b, at + 4)
                val bl = sample(b, at + 8); val br = sample(b, at + 10)
                l[dst] = ((fl + 0.707 * fc + 0.707 * bl) / 1.707).toInt()
                r[dst] = ((fr + 0.707 * fc + 0.707 * br) / 1.707).toInt()
            }
            else -> { l[dst] = sample(b, at); r[dst] = sample(b, at + 2) }
        }
    }

    companion object {
        const val OUT_RATE = 48000
    }
}

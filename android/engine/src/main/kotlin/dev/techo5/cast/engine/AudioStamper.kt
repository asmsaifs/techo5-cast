package dev.techo5.cast.engine

import kotlin.math.abs

/**
 * Stamps audio chunks so that consecutive ones abut exactly.
 *
 * The [Timeline] is re-made from every video frame, and the frames' release times jitter by a few
 * ms. The device places a chunk by its stamp to the frame, so stamping each chunk from the timeline
 * leaves gaps and overlaps: audible ticks. Chunks are contiguous in the clip, so each is stamped right
 * after the previous one, and the timeline is followed only when it has moved for real (a pause, a
 * stall, a shift), by more than [resyncUs].
 */
class AudioStamper(private val resyncUs: Long = 30_000) {
    private var epoch = -1
    private var mediaUs = 0L
    private var stampUs = 0L

    /** The stamp for a chunk of [chunkEpoch] at [chunkMediaUs] that the timeline says is due at [at]. */
    fun stampFor(chunkEpoch: Int, chunkMediaUs: Long, at: Long): Long {
        if (chunkEpoch != epoch) return at
        val follow = stampUs + (chunkMediaUs - mediaUs)
        return if (abs(at - follow) < resyncUs) follow else at
    }

    /** The chunk was sent with [stamp]; the next one follows it. */
    fun placed(chunkEpoch: Int, chunkMediaUs: Long, stamp: Long) {
        epoch = chunkEpoch
        mediaUs = chunkMediaUs
        stampUs = stamp
    }

    /** The next chunk is placed from the timeline afresh. */
    fun forget() {
        epoch = -1
    }
}

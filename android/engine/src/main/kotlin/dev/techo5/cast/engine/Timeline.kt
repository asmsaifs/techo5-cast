package dev.techo5.cast.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * One clock for picture and sound. A stamp on the wire means *when to show this* on the phone's
 * monotonic clock, not where in the clip it is (docs/protocol.md, "Stalls").
 *
 * The video side has the real answer: ExoPlayer says, per frame, the media time and the wall-clock
 * moment it will release it ([anchor]). Audio has no such callback, so it borrows that mapping:
 * a sample at media time `m` is due at `anchor.clock + (m - anchor.media)`.
 *
 * Seeks and flushes make the old mapping wrong; [newEpoch] throws it away, and data tagged with an
 * older epoch can no longer be placed ([clockFor] returns null), so it is dropped, not mis-stamped.
 * Pausing only [invalidate]s the anchor: what was decoded before the pause is still to be played
 * after it, so it waits for the next frame's anchor instead.
 */
class Timeline {
    private class Anchor(val epoch: Int, val mediaUs: Long, val clockUs: Long)

    private val epochValue = AtomicInteger()
    @Volatile private var current: Anchor? = null

    val epoch: Int get() = epochValue.get()

    /** Media time [mediaUs] is (or will be) shown at [clockUs]. */
    fun anchor(mediaUs: Long, clockUs: Long) {
        current = Anchor(epochValue.get(), mediaUs, clockUs)
    }

    /** Forget the mapping and everything tagged with the old epoch (seek, flush). */
    fun newEpoch(): Int {
        current = null
        return epochValue.incrementAndGet()
    }

    /** Forget the mapping but keep the epoch (pause, resume). */
    fun invalidate() {
        current = null
    }

    /** When media time [mediaUs] of [forEpoch] is due, or null if it cannot be placed (yet). */
    fun clockFor(forEpoch: Int, mediaUs: Long): Long? {
        val a = current ?: return null
        if (a.epoch != forEpoch) return null
        return a.clockUs + (mediaUs - a.mediaUs)
    }
}

package dev.techo5.cast.engine

/**
 * Picks the video frame rate from how the link is doing (docs/android-app-plan.md 5.5): a step down
 * (30 -> 24 -> 15) when frames are being dropped for lack of a free socket, a step up after a quiet
 * minute. Feed it the sender's counters about once a second.
 */
class RateAdapter(private val steps: List<Int> = listOf(30, 24, 15), start: Int = steps.first()) {
    private var index = steps.indexOf(start).coerceAtLeast(0)
    private var lastSent = 0L
    private var lastDropped = 0L
    private var bad = 0
    private var quiet = 0

    val fps: Int get() = steps[index]

    /** The new rate if it changed, else null. */
    fun update(videoSent: Long, videoDropped: Long): Int? {
        val sent = videoSent - lastSent
        val dropped = videoDropped - lastDropped
        lastSent = videoSent
        lastDropped = videoDropped
        val total = sent + dropped
        when {
            total >= 5 && dropped * 5 > total -> { bad++; quiet = 0 }
            dropped * 50 <= total -> { bad = 0; quiet++ }
            else -> { bad = 0; quiet = 0 }
        }
        if (bad >= 2 && index < steps.lastIndex) {
            index++; bad = 0; quiet = 0
            return fps
        }
        if (quiet >= QUIET_SECONDS && index > 0) {
            index--; quiet = 0
            return fps
        }
        return null
    }

    private companion object {
        const val QUIET_SECONDS = 60
    }
}

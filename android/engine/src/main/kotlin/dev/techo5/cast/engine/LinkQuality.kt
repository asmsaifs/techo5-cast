package dev.techo5.cast.engine

import dev.techo5.cast.protocol.Stats

/**
 * Whether the picture is arriving well, from the Show's own reports (docs/protocol.md, Stats): the last
 * few seconds' dropped frames against shown ones. Feed it the latest [Stats] about once a second.
 */
class LinkQuality {
    private var lastShown = 0
    private var lastDropped = 0
    private var last: Stats? = null
    private val window = ArrayDeque<Pair<Int, Int>>() // (shown, dropped) per report

    /** True if smooth, false if struggling, null if the Show has not reported (or is an older one). */
    fun update(stats: Stats?): Boolean? {
        if (stats == null) return null
        if (stats === last) return verdict() // the same report again: no new second to count
        last = stats
        // A new cast starts its totals again from zero.
        if (stats.shown < lastShown || stats.dropped < lastDropped) { lastShown = 0; lastDropped = 0; window.clear() }
        window.addLast((stats.shown - lastShown) to (stats.dropped - lastDropped))
        lastShown = stats.shown
        lastDropped = stats.dropped
        while (window.size > WINDOW) window.removeFirst()
        return verdict()
    }

    private fun verdict(): Boolean? {
        val shown = window.sumOf { it.first }
        val dropped = window.sumOf { it.second }
        val total = shown + dropped
        if (total < 10) return true // too little to say otherwise: a still picture sends nothing
        return dropped * 10 <= total // up to one frame in ten lost is smooth
    }

    private companion object {
        const val WINDOW = 3
    }
}

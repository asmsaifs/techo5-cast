package dev.techo5.cast.engine

import dev.techo5.cast.protocol.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkQualityTest {
    private fun stats(shown: Int, dropped: Int) = Stats(shown, dropped, 0, 0)

    @Test fun unknownWithoutAReport() {
        assertNull(LinkQuality().update(null))
    }

    @Test fun smoothWhenFewFramesAreLost() {
        val q = LinkQuality()
        var shown = 0
        var dropped = 0
        var verdict: Boolean? = null
        repeat(5) { shown += 30; dropped += 1; verdict = q.update(stats(shown, dropped)) }
        assertEquals(true, verdict)
    }

    @Test fun strugglingWhenManyAreLost() {
        val q = LinkQuality()
        var shown = 0
        var dropped = 0
        var verdict: Boolean? = null
        repeat(4) { shown += 15; dropped += 15; verdict = q.update(stats(shown, dropped)) }
        assertEquals(false, verdict)
    }

    @Test fun recoversOnceTheBadSecondsLeaveTheWindow() {
        val q = LinkQuality()
        var shown = 0
        var dropped = 0
        repeat(3) { shown += 10; dropped += 20; q.update(stats(shown, dropped)) }
        var verdict: Boolean? = null
        repeat(3) { shown += 30; verdict = q.update(stats(shown, dropped)) }
        assertEquals(true, verdict)
    }

    @Test fun aStillPictureIsNotStruggling() {
        val q = LinkQuality()
        assertEquals(true, q.update(stats(2, 1)))
    }

    @Test fun aNewCastStartsTheCountAgain() {
        val q = LinkQuality()
        q.update(stats(100, 50))
        assertEquals(true, q.update(stats(30, 0)))
    }
}

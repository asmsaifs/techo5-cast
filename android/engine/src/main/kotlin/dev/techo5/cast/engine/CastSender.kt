package dev.techo5.cast.engine

import dev.techo5.cast.protocol.Hello
import dev.techo5.cast.protocol.Kind
import dev.techo5.cast.protocol.Secure
import dev.techo5.cast.protocol.Stats
import dev.techo5.cast.protocol.parseStats
import dev.techo5.cast.protocol.Welcome
import dev.techo5.cast.protocol.dial
import dev.techo5.cast.protocol.parseWelcome
import dev.techo5.cast.protocol.readMessage
import dev.techo5.cast.protocol.stamp
import dev.techo5.cast.protocol.toJson
import dev.techo5.cast.protocol.writeMessage
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque

/** The phone's monotonic clock in microseconds: what every stamp on the wire is on. */
fun nowUs(): Long = System.nanoTime() / 1000

/** What the sender has done so far; for the screen and for tuning. */
data class SenderStats(
    val videoSent: Long,
    val videoDropped: Long,
    val audioSent: Long,
    val audioDropped: Long,
    val bytesSent: Long,
    val worstWriteMs: Long,
    /** The Show's own report (frames shown and dropped), or null before the first, or from an older Show. */
    val device: Stats? = null,
)

/** Why a cast ended, for the person to read. */
class CastEnded(val reason: String, val lost: Boolean = false)

/**
 * The connection to the Show and the loop that feeds it (docs/android-app-plan.md 5.5).
 *
 * Video is droppable, audio is not: there is room for one video frame, and a newer one replaces it;
 * audio chunks queue (up to a few seconds). One writer thread sends whichever waiting message is due
 * first, and holds it back until it is no more than the device's `latency_ms` ahead of real time (the
 * protocol forbids sending further ahead). A clock message goes out first and then about every second,
 * which also keeps the device from ending a quiet cast.
 *
 * Stall handling (moving both streams' clock forward after a stall) is not here yet: that is M3.
 * What is here already never sends a frame that is more than 100 ms late.
 */
class CastSender private constructor(
    private val secure: Secure,
    private val socket: Socket,
    val welcome: Welcome,
    /** How many times smaller than the screen the frames are (2 = half size), as asked in the hello. */
    val scale: Int,
    private val timeline: Timeline,
    private val onEnded: (CastEnded) -> Unit,
) : Closeable {
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private var video: Pair<Long, ByteArray>? = null
    private val audio = ArrayDeque<AudioChunk>()
    private var audioWaitingSince = 0L
    private val stamper = AudioStamper()
    // How far behind real time everything is being shown, after stalls (see pickNext). Both streams
    // move together, so picture and sound stay in step.
    private var shiftUs = 0L
    @Volatile private var closed = false
    @Volatile private var deviceStats: Stats? = null

    private var videoSent = 0L
    private var videoDropped = 0L
    private var audioSent = 0L
    private var audioDropped = 0L
    private var bytesSent = 0L
    private var worstWriteMs = 0L

    // Sending ahead of real time by no more than what the device buffers, less some margin.
    private val aheadUs = ((welcome.latencyMs * 1000L) - 60_000L).coerceAtLeast(40_000L)

    private val writer = Thread(::writeLoop, "cast-writer")
    private val reader = Thread(::readLoop, "cast-reader")

    fun offerVideo(stampUs: Long, jpeg: ByteArray) {
        synchronized(lock) {
            if (video != null) videoDropped++
            video = stampUs to jpeg
            lock.notifyAll()
        }
    }

    fun offerAudio(chunk: AudioChunk) {
        synchronized(lock) {
            if (audio.isEmpty()) audioWaitingSince = nowUs()
            audio.add(chunk)
            // A few seconds at most; past that the writer is far behind and the oldest is no use.
            while (audio.size > MAX_AUDIO_CHUNKS) { audio.poll(); audioDropped++ }
            lock.notifyAll()
        }
    }

    /** Forget everything waiting: after a seek, what is queued is from the wrong place. */
    fun clearQueued() {
        synchronized(lock) {
            video = null
            audioDropped += audio.size
            audio.clear()
            shiftUs = 0
            stamper.forget()
        }
    }

    /** Back on real time: after a pause or a seek, whatever delay a stall added no longer applies. */
    fun resetShift() {
        synchronized(lock) {
            shiftUs = 0
            stamper.forget()
        }
    }

    fun stats(): SenderStats = synchronized(lock) {
        SenderStats(videoSent, videoDropped, audioSent, audioDropped, bytesSent, worstWriteMs, deviceStats)
    }

    private fun start() {
        writer.start()
        reader.start()
    }

    private fun writeLoop() {
        try {
            secure.writeMessage(Kind.CLOCK, stamp(nowUs()))
            var nextClock = nowUs() + 1_000_000
            while (!closed) {
                val now = nowUs()
                if (now >= nextClock) {
                    secure.writeMessage(Kind.CLOCK, stamp(now))
                    nextClock = now + 1_000_000
                }
                val next = pickNext(now)
                if (next == null) continue
                val t0 = System.nanoTime()
                if (next.videoJpeg != null) {
                    secure.writeMessage(Kind.VIDEO, stamp(next.stampUs), next.videoJpeg)
                } else {
                    secure.writeMessage(Kind.AUDIO, stamp(next.stampUs), next.audioPcm!!)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                synchronized(lock) {
                    bytesSent += (next.videoJpeg?.size ?: next.audioPcm!!.size) + 13
                    if (next.videoJpeg != null) videoSent++ else audioSent++
                    if (ms > worstWriteMs) worstWriteMs = ms
                }
            }
        } catch (e: Exception) {
            if (!closed) end(CastEnded("connection lost: ${e.message}", lost = true))
        }
    }

    private class Outgoing(val stampUs: Long, val videoJpeg: ByteArray?, val audioPcm: ByteArray?)

    /** The message due first that may be sent now; null after waiting a moment if there is none. */
    private fun pickNext(now: Long): Outgoing? = synchronized(lock) {
        // Video: drop it if it is late, otherwise it is ready with its stamp.
        video?.let { (stamp, _) ->
            if (stamp + shiftUs < now - LATE_VIDEO_US) { video = null; videoDropped++ }
        }
        // Audio: place the head on the clock. Old epoch: from before a seek, drop. No anchor yet: wait
        // for the first video frame's, or, for a file with no picture, give up waiting and anchor on now.
        var audioStamp: Long? = null
        while (true) {
            val head = audio.peek() ?: break
            if (head.epoch != timeline.epoch) { audio.poll(); audioDropped++; continue }
            val raw = timeline.clockFor(head.epoch, head.mediaUs)
            if (raw == null) {
                if (now - audioWaitingSince > ANCHOR_WAIT_US) {
                    timeline.anchor(head.mediaUs, now + aheadUs / 2)
                    continue
                }
                break
            }
            var at = raw + shiftUs
            if (at < now - STALL_US) {
                // Behind by more than a hiccup: the link or the player stalled. Rather than throw sound
                // away, show everything later by the delay, picture and sound together, up to a cap.
                val late = now - at
                if (shiftUs + late <= MAX_SHIFT_US) {
                    shiftUs += late
                    at += late
                    stamper.forget()
                } else {
                    audio.poll(); audioDropped++; continue
                }
            }
            audioStamp = stamper.stampFor(head.epoch, head.mediaUs, at)
            break
        }
        val v = video
        val videoStamp = v?.let { it.first + shiftUs }
        val pickVideo = v != null && (audioStamp == null || videoStamp!! <= audioStamp)
        val stamp = if (pickVideo) videoStamp else audioStamp
        if (stamp == null || stamp - now > aheadUs) {
            // Nothing due: wait for news or for time to pass, but not long: the clock message is due too.
            lock.wait(if (stamp == null) 20 else ((stamp - now - aheadUs) / 1000).coerceIn(1, 20))
            return@synchronized null
        }
        if (pickVideo) {
            video = null
            Outgoing(stamp, v!!.second, null)
        } else {
            val chunk = audio.poll()!!
            stamper.placed(chunk.epoch, chunk.mediaUs, stamp)
            audioWaitingSince = now
            Outgoing(stamp, null, chunk.pcm)
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = secure.readMessage()
                when (m.kind) {
                    Kind.STOP -> {
                        end(CastEnded("the Show ended the cast"))
                        return
                    }
                    Kind.STATS -> try {
                        deviceStats = parseStats(String(m.payload, Charsets.UTF_8))
                    } catch (_: Exception) {} // a report we cannot read is not worth a cast
                }
            }
        } catch (e: Exception) {
            if (!closed) end(CastEnded("connection lost: ${e.message}", lost = true))
        }
    }

    private fun end(why: CastEnded) {
        if (closed) return
        close()
        onEnded(why)
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { lock.notifyAll() }
        try { secure.writeMessage(Kind.BYE) } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    companion object {
        private const val LATE_VIDEO_US = 100_000L
        private const val STALL_US = 300_000L
        private const val MAX_SHIFT_US = 5_000_000L
        private const val ANCHOR_WAIT_US = 500_000L
        private const val MAX_AUDIO_CHUNKS = 200 // 4 s

        class Refused(val reason: String) : Exception(reason)

        /** Dials a Show, does the handshake and the hello; throws [Refused] if it says no. Call off the
         *  main thread. */
        fun connect(
            host: String,
            port: Int,
            key: String,
            name: String,
            scale: Int,
            title: String = "",
            video: Boolean,
            audio: Boolean,
            timeline: Timeline,
            onEnded: (CastEnded) -> Unit,
        ): CastSender {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 10_000 // for the handshake and welcome only
                val secure = dial(socket, key)
                secure.writeMessage(
                    Kind.HELLO,
                    Hello(name = name, video = video, audio = audio, rate = 48000, channels = 2, scale = scale, title = title)
                        .toJson().toByteArray(Charsets.UTF_8),
                )
                // The Show may be waiting for somebody there to accept the phone (20 s on its side).
                socket.soTimeout = 35_000
                val welcome = parseWelcome(String(secure.readMessage().payload, Charsets.UTF_8))
                if (!welcome.ok) throw Refused(welcome.reason ?: "the Show refused")
                socket.soTimeout = 0
                return CastSender(secure, socket, welcome, scale, timeline, onEnded).also { it.start() }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}

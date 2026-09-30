package dev.techo5.cast.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The biggest even-sized rectangle of the source's shape that fits the box (docs/android-app-plan.md 5.6:
 * the phone's picture is letterboxed into the Show's screen).
 */
fun fitEven(srcW: Int, srcH: Int, boxW: Int, boxH: Int): Pair<Int, Int> {
    val k = min(boxW.toFloat() / srcW, boxH.toFloat() / srcH)
    val w = (srcW * k).toInt() and 1.inv()
    val h = (srcH * k).toInt() and 1.inv()
    return max(w, 2) to max(h, 2)
}

/**
 * The phone's screen and the sound of its apps, sent to a Show (docs/android-app-plan.md 5.6). Same
 * pipeline as [CastEngine] with other sources: a [MediaProjection] virtual display draws into the
 * [FrameGrabber]'s surface, and playback capture feeds 20 ms chunks of sound in place of the audio tap.
 *
 * There is no media time, so stamps are the capture time itself and the [Timeline] is the identity
 * (media time = clock time). Create and call [start] from the main thread.
 *
 * @param screenW the phone's screen size in pixels; the virtual display keeps its shape.
 * @param audio capture sound too (needs RECORD_AUDIO); the hello must have announced it.
 */
class MirrorEngine(
    initialSender: CastSender,
    private val timeline: Timeline,
    private val projection: MediaProjection,
    private val screenW: Int,
    private val screenH: Int,
    private val densityDpi: Int,
    val audio: Boolean,
    context: Context,
    private val onEnded: (String) -> Unit,
) {
    @Volatile private var sender = initialSender
    @Volatile private var stopped = false
    private val welcome = initialSender.welcome
    private val scale = initialSender.scale.coerceAtLeast(1)

    private val grabber = FrameGrabber(
        outW = welcome.w / scale,
        outH = welcome.h / scale,
        stampFor = { nowUs() },
        onFrame = { stamp, jpeg -> this.sender.offerVideo(stamp, jpeg) },
    )
    private var display: VirtualDisplay? = null
    private var record: AudioRecord? = null
    private var audioThread: Thread? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mutedPhone = false

    private val projectionCallback = object : MediaProjection.Callback() {
        // The person ended it from the system's "sharing your screen" chip or notification.
        override fun onStop() {
            if (!stopped) end("Screen sharing was stopped.")
        }
    }

    val quality: Int get() = grabber.quality

    init {
        timeline.anchor(0, 0)
    }

    fun setQuality(jpegQuality: Int, maxFps: Int) {
        grabber.quality = jpegQuality
        grabber.maxFps = maxFps
    }

    /** After a lost connection: carry on to a new [CastSender]. Nothing to seek back to. */
    fun replaceSender(next: CastSender) {
        val old = sender
        sender = next
        old.close()
        timeline.anchor(0, 0)
    }

    fun start() {
        val (w, h) = fitEven(screenW, screenH, welcome.w, welcome.h)
        val surface = grabber.start(w, h)
        grabber.setVideoAspect(w.toFloat() / h)
        // Since Android 14 a callback has to be registered before the display is created.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "techo5-cast", w, h, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )
        if (audio) startAudio()
    }

    /** The phone turned (or its screen changed shape): follow it, or the picture shrinks into the old shape. */
    fun resize(newScreenW: Int, newScreenH: Int, newDensityDpi: Int) {
        if (stopped || newScreenW <= 0 || newScreenH <= 0) return
        val (w, h) = fitEven(newScreenW, newScreenH, welcome.w, welcome.h)
        grabber.setBufferSize(w, h)
        display?.resize(w, h, newDensityDpi)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller, which passes audio = false without it
    private fun startAudio() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Resampler.OUT_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(Resampler.OUT_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuffer, CHUNK_BYTES * 8))
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "cannot capture the phone's sound" }
        record = rec
        rec.startRecording()
        audioThread = Thread(::audioLoop, "mirror-audio").also { it.start() }
        // Playback capture copies the sound and leaves the phone playing it; the Show should be the
        // only place it is heard, so the media stream is muted until the mirror ends.
        if (!audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            mutedPhone = true
        }
    }

    /** Reads are paced by the sound arriving, so a chunk is stamped with the moment it was heard; the
     *  count keeps consecutive chunks exactly 20 ms apart, and it restarts if the thread fell far behind. */
    private fun audioLoop() {
        val rec = record ?: return
        var base = 0L
        var frames = 0L
        while (!stopped) {
            val buf = ByteArray(CHUNK_BYTES)
            var got = 0
            while (got < CHUNK_BYTES && !stopped) {
                val n = rec.read(buf, got, CHUNK_BYTES - got)
                if (n < 0) return
                got += n
            }
            if (stopped) return
            val heardAt = nowUs() - CHUNK_US // the chunk read covers the 20 ms before now
            var mediaUs = base + frames * 1_000_000L / Resampler.OUT_RATE
            if (frames == 0L || abs(heardAt - mediaUs) > RESTART_US) {
                base = heardAt
                frames = 0
                mediaUs = heardAt
            }
            sender.offerAudio(AudioChunk(timeline.epoch, mediaUs, buf))
            frames += CHUNK_BYTES / 4
        }
    }

    fun state() = CastState(
        playing = true,
        sender = sender.stats(),
        grabAvgMs = grabber.avgWorkUs / 1000f,
        grabSkipped = grabber.skipped,
        live = true,
    )

    private fun end(reason: String) {
        stop()
        onEnded(reason)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        if (mutedPhone) {
            mutedPhone = false
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        display?.release()
        display = null
        projection.unregisterCallback(projectionCallback)
        projection.stop()
        grabber.stop()
        sender.close()
    }

    private companion object {
        const val CHUNK_US = 20_000L
        const val RESTART_US = 200_000L
    }
}

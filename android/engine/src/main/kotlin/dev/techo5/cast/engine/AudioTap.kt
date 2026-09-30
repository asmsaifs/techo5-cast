package dev.techo5.cast.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** One 20 ms piece of the device's audio: 960 frames of 48 kHz stereo S16LE. */
const val CHUNK_BYTES = 960 * 4

/** A piece of sound and where in the clip its first sample is. [epoch] says which [Timeline] epoch
 *  that media time belongs to. */
class AudioChunk(val epoch: Int, val mediaUs: Long, val pcm: ByteArray)

/**
 * Sits in ExoPlayer's audio chain (docs/android-app-plan.md 5.3): converts whatever the decoder gave
 * to 48 kHz stereo 16-bit, which is also what it hands on to the (muted) speaker, and copies it out in
 * 20 ms chunks. It sits before the sink's volume, so `player.volume = 0` silences the phone and not
 * the tap.
 *
 * The media time of a chunk is the presentation time of the first buffer after the last flush plus
 * the samples counted since; [AudioTapSink] supplies that first time, since a processor never sees it.
 * Runs on the playback thread, so [onChunk] must be quick.
 */
class AudioTap(
    private val timeline: Timeline,
    private val onChunk: (AudioChunk) -> Unit,
) : BaseAudioProcessor() {
    private var resampler: Resampler? = null
    private var needBase = true
    private var baseUs = 0L
    private var framesOut = 0L
    private var pending = ByteArray(CHUNK_BYTES * 2)
    private var pendingLen = 0

    /** The media time of the buffer the sink is being handed (its presentation time less the
     *  renderer's stream offset, so it is on the same scale as the video frames' times). The first
     *  one after a flush becomes the origin of the sample count; that is applied in [queueInput],
     *  because the sink may flush the processors inside the very call that hands over the buffer. */
    @Volatile var currentBufferUs = 0L

    fun discontinuity() {
        needBase = true
        pendingLen = 0
        resampler?.reset()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        resampler = Resampler(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return AudioFormat(Resampler.OUT_RATE, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        // A seek or a reconfiguration: whatever mapping from media time to clock we had is gone, and so
        // is any chunk still waiting to be sent with the old epoch.
        timeline.newEpoch()
        discontinuity()
    }

    override fun onReset() {
        resampler = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (needBase) {
            needBase = false
            baseUs = currentBufferUs
            framesOut = 0
            pendingLen = 0
        }
        val size = inputBuffer.remaining()
        val input = ByteArray(size)
        inputBuffer.get(input)
        val out = resampler!!.process(input, 0, size)
        replaceOutputBuffer(out.size).put(out).flip()
        emit(out)
    }

    private fun emit(pcm: ByteArray) {
        if (pendingLen + pcm.size > pending.size) pending = pending.copyOf(pendingLen + pcm.size)
        System.arraycopy(pcm, 0, pending, pendingLen, pcm.size)
        pendingLen += pcm.size
        var at = 0
        while (pendingLen - at >= CHUNK_BYTES) {
            val mediaUs = baseUs + framesOut * 1_000_000L / Resampler.OUT_RATE
            onChunk(AudioChunk(timeline.epoch, mediaUs, pending.copyOfRange(at, at + CHUNK_BYTES)))
            framesOut += CHUNK_BYTES / 4
            at += CHUNK_BYTES
        }
        if (at > 0) {
            System.arraycopy(pending, at, pending, 0, pendingLen - at)
            pendingLen -= at
        }
    }
}

/** Tells the [AudioTap] the presentation time of what the sink is being fed. */
class AudioTapSink(sink: AudioSink, private val tap: AudioTap) : ForwardingAudioSink(sink) {
    private var streamOffsetUs = 0L

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        tap.currentBufferUs = presentationTimeUs - streamOffsetUs
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun handleDiscontinuity() {
        tap.discontinuity()
        super.handleDiscontinuity()
    }
}

/** ExoPlayer's renderers with [tap] in the audio chain. */
class TapRenderersFactory(context: Context, private val tap: AudioTap) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(tap))
            .setEnableFloatOutput(false) // the tap takes 16-bit; the sink converts anything wider first
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
        return AudioTapSink(sink, tap)
    }
}

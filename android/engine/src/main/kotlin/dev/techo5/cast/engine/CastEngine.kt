package dev.techo5.cast.engine

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.LinkedHashMap

/** What the person sees while casting. */
data class CastState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val sender: SenderStats? = null,
    val grabAvgMs: Float = 0f,
    val grabSkipped: Long = 0,
    val ended: String? = null,
    /** Mirroring the screen: no position, pause or seek. */
    val live: Boolean = false,
)

/** What to play: a picture stream and, if the sound is a separate stream, that too. */
class PlayItem(
    val video: Uri,
    val audio: Uri? = null,
    val videoHeaders: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = emptyMap(),
)

/**
 * A file or a link, played by ExoPlayer with no picture or sound on the phone, and sent to a Show
 * (docs/android-app-plan.md section 5). Create and call from the main thread.
 *
 *   player -> surface -> [FrameGrabber] -> JPEG ----+
 *          -> audio chain -> [AudioTap] -> PCM -----+-> [CastSender] -> the Show
 *   [Timeline]: the player's per-frame release times give both streams one clock
 */
class CastEngine(private val context: Context, initialSender: CastSender, private val timeline: Timeline) {
    @Volatile private var sender = initialSender
    private val welcome = initialSender.welcome
    private val scale = initialSender.scale.coerceAtLeast(1)

    /** Presentation time -> the clock time ExoPlayer said it would release that frame at. */
    private val releases = object : LinkedHashMap<Long, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>) = size > 32
    }

    private val grabber = FrameGrabber(
        outW = welcome.w / scale,
        outH = welcome.h / scale,
        stampFor = { pts -> synchronized(releases) { releases[pts] } ?: nowUs() },
        onFrame = { stamp, jpeg -> this.sender.offerVideo(stamp, jpeg) },
    )
    private val tap = AudioTap(timeline) { this.sender.offerAudio(it) }
    private var player: ExoPlayer? = null
    private var onState: (CastState) -> Unit = {}
    private var endedReason: String? = null

    val quality: Int get() = grabber.quality
    val maxFps: Int get() = grabber.maxFps

    /** Called when the player fails, with the position; true if it took care of it (a retry), so the
     *  cast is not ended. */
    var onSourceError: ((PlaybackException, Long) -> Boolean)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** After a lost connection: carry on to a new [CastSender] at the same place. */
    fun replaceSender(next: CastSender) {
        val old = sender
        sender = next
        old.close()
        timeline.invalidate()
    }

    /** Starts [item] again from [startMs] (expired links: extracted afresh). */
    fun load(item: PlayItem, startMs: Long) {
        val p = player ?: return
        timeline.newEpoch()
        sender.clearQueued()
        p.setMediaSource(mediaSource(item), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    fun setQuality(jpegQuality: Int, maxFps: Int) {
        grabber.quality = jpegQuality
        grabber.maxFps = maxFps
    }

    /** Starts casting [uri] (a content:// or file:// URI, or a direct http(s) link). */
    fun play(uri: Uri, onState: (CastState) -> Unit) = play(PlayItem(uri), onState)

    fun play(item: PlayItem, onState: (CastState) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "main thread only" }
        this.onState = onState
        val surface = grabber.start()
        val p = ExoPlayer.Builder(context, TapRenderersFactory(context, tap)).build()
        player = p
        p.volume = 0f // the phone stays silent; the tap sits before the volume
        p.setVideoSurface(surface)
        p.setVideoFrameMetadataListener(VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, format, _ ->
            val now = System.nanoTime()
            val clockUs = (if (releaseTimeNs == C.TIME_UNSET) now else maxOf(releaseTimeNs, now)) / 1000
            synchronized(releases) { releases[presentationTimeUs] = clockUs }
            timeline.anchor(presentationTimeUs, clockUs)
        })
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    grabber.setVideoAspect(size.width * size.pixelWidthHeightRatio / size.height)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Everything decoded before a pause is still to be played after it, but when it plays is
                // new: the mapping is re-made from the first frame after.
                timeline.invalidate()
                sender.resetShift()
                publish()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    sender.clearQueued()
                }
                publish()
            }

            override fun onPlaybackStateChanged(state: Int) = publish()

            override fun onPlayerError(error: PlaybackException) {
                if (onSourceError?.invoke(error, p.currentPosition) == true) return
                end("can't play this: ${error.errorCodeName}")
            }
        })
        p.setMediaSource(mediaSource(item))
        p.prepare()
        p.playWhenReady = true
    }

    /** One source, or picture and sound merged (what YouTube serves), each with its own headers. */
    private fun mediaSource(item: PlayItem): MediaSource {
        fun one(uri: Uri, headers: Map<String, String>): MediaSource {
            val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setAllowCrossProtocolRedirects(true)
            return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)).createMediaSource(MediaItem.fromUri(uri))
        }
        val video = one(item.video, item.videoHeaders)
        val audio = item.audio ?: return video
        return MergingMediaSource(video, one(audio, item.audioHeaders))
    }

    fun pause() { player?.pause() }
    fun resume() { player?.play() }
    fun seekTo(ms: Long) { player?.seekTo(ms) }

    /** The state as it is now; call about once a second from the UI, since position is not pushed. */
    fun publish() {
        val p = player ?: return
        onState(
            CastState(
                playing = p.isPlaying,
                positionMs = p.currentPosition,
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0,
                sender = sender.stats(),
                grabAvgMs = grabber.avgWorkUs / 1000f,
                grabSkipped = grabber.skipped,
                ended = endedReason,
            ),
        )
    }

    /** The Show ended the cast or the connection dropped. */
    fun end(reason: String) {
        endedReason = reason
        publish()
        stop()
    }

    fun stop() {
        player?.release()
        player = null
        grabber.stop()
        sender.close()
    }
}

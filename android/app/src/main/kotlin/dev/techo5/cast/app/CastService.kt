package dev.techo5.cast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.IntentCompat
import dev.techo5.cast.engine.LinkQuality
import dev.techo5.cast.engine.MirrorEngine
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.DeviceStore
import dev.techo5.cast.engine.CastEngine
import dev.techo5.cast.engine.CastSender
import androidx.media3.common.PlaybackException
import dev.techo5.cast.engine.CastState
import dev.techo5.cast.engine.RateAdapter
import dev.techo5.cast.engine.PlayItem
import dev.techo5.cast.extract.ExtractFailed
import dev.techo5.cast.extract.YtDlpExtractor
import dev.techo5.cast.extract.isDirectMedia
import dev.techo5.cast.engine.Timeline
import dev.techo5.cast.engine.CastSender.Companion.Refused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "castsvc"

/** What the app shows about the cast in progress. */
sealed interface Session {
    data object Idle : Session
    data class Connecting(val device: String, val step: String = "Connecting") : Session
    data class Casting(val device: String, val title: String, val state: CastState) : Session
    /** The cast is over; [reason] is a sentence for the person, or null after a normal stop. */
    data class Ended(val reason: String?) : Session
}

/**
 * Owns the engine and the connection, so casting goes on with the screen off and the app closed
 * (docs/android-app-plan.md 8.2). Started from a visible action with the file in the intent's data
 * and the device's address; controlled with the other actions.
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: CastEngine? = null
    private var mirror: MirrorEngine? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var device = ""
    private var title = ""
    private var latest = CastState()
    private var target: Device? = null
    private var timeline = Timeline()
    private var link: String? = null
    private var reconnecting = false
    private var lastRetryMs = 0L
    private var rate = RateAdapter()
    private var settings = Settings()
    private var link_ = LinkQuality()
    private var smooth: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mirror?.let { m ->
            val dm = screenSize()
            m.resize(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_MIRROR -> startMirror(intent)
            ACTION_PAUSE -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
            ACTION_SEEK -> engine?.seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0))
            ACTION_STOP -> finish(null)
            else -> if (engine == null && mirror == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val uri = intent.data
        val deviceId = intent.getStringExtra(EXTRA_DEVICE)
        val target = deviceId?.let { DeviceStore(this).find(it) }
        if (uri == null || target == null) {
            // startForegroundService promises a startForeground call; keep it before quitting.
            startForeground(NOTIFICATION_ID, notification("TECHO5 Cast", "Nothing to cast", false))
            finish("That device is not saved any more.")
            return
        }
        stopEngine()
        this.target = target
        settings = Settings.load(this)
        link = uri.toString().takeIf { (uri.scheme == "http" || uri.scheme == "https") && !isDirectMedia(it) }
        device = target.name
        title = intent.getStringExtra(EXTRA_TITLE) ?: uri.lastPathSegment ?: "Video"
        Session_.value = Session.Connecting(device)
        startForeground(
            NOTIFICATION_ID,
            notification("Casting to $device", title, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquireLocks()
        scope.launch {
            val item = resolve(uri) ?: return@launch
            connect(target, item)
        }
    }

    /** Mirror mode (docs/android-app-plan.md 5.6): started by [MirrorActivity] with the result of the
     *  system's screen-capture consent. The foreground service of type mediaProjection has to be up
     *  before the projection is taken from the consent. */
    private fun startMirror(intent: Intent) {
        val target = intent.getStringExtra(EXTRA_DEVICE)?.let { DeviceStore(this).find(it) }
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (target == null || data == null) {
            startForeground(
                NOTIFICATION_ID, notification("TECHO5 Cast", "Nothing to mirror", false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            finish("Screen sharing did not start.")
            return
        }
        stopEngine()
        this.target = target
        settings = Settings.load(this)
        link = null
        device = target.name
        title = "Mirroring this phone"
        Session_.value = Session.Connecting(device)
        startForeground(
            NOTIFICATION_ID,
            notification("Mirroring to $device", "Screen and sound", false, live = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        acquireLocks()
        // Android ends a screen capture when the lock screen appears (STOP_REASON_KEYGUARD), which is
        // what the screen timeout leads to; keep the screen on, dimmed, for as long as we mirror.
        @Suppress("DEPRECATION")
        if (screenLock == null) {
            screenLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "techo5cast:mirror").apply { acquire() }
        }
        val projection = try {
            (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        } catch (e: SecurityException) {
            null
        }
        if (projection == null) {
            finish("Screen capture was not allowed.")
            return
        }
        val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
        scope.launch { connectMirror(target, projection, audio) }
    }

    private suspend fun connectMirror(target: Device, projection: MediaProjection, audio: Boolean) {
        timeline = Timeline()
        rate = RateAdapter(settings.fpsSteps)
        link_ = LinkQuality()
        smooth = null
        AppLog.i(TAG, "mirroring to ${target.name} (${target.host}:${target.port}), audio $audio, scale ${settings.scale}, up to ${settings.effectiveFps} fps")
        val sender = try {
            dial(target, audio)
        } catch (e: Refused) {
            projection.stop()
            finish("$device refused: ${e.message}")
            return
        } catch (e: Exception) {
            projection.stop()
            finish(describeFailure(e, device))
            return
        }
        val dm = screenSize()
        val mirror = MirrorEngine(
            sender, timeline, projection, dm.widthPixels, dm.heightPixels, dm.densityDpi, audio,
        ) { reason -> scope.launch { finish(reason) } }
        this.mirror = mirror
        mirror.setQuality(settings.effectiveQuality, rate.fps)
        try {
            mirror.start()
        } catch (e: Exception) {
            AppLog.i(TAG, "mirror start failed: ${e.javaClass.simpleName}: ${e.message}")
            finish("Could not start mirroring: ${e.message}")
            return
        }
        ticker = scope.launch {
            while (true) {
                val state = mirror.state()
                smooth = link_.update(state.sender?.device)
                latest = state.copy(smooth = smooth)
                state.sender?.let { s ->
                    rate.update(s.videoSent, s.videoDropped + (s.device?.dropped ?: 0))
                        ?.let { mirror.setQuality(mirror.quality, it) }
                }
                if (!reconnecting) Session_.value = Session.Casting(device, title, latest)
                delay(1000)
            }
        }
    }

    /** The real size of the screen, navigation bar included, which is what the capture shows. */
    private fun screenSize(): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            dm.widthPixels = b.width()
            dm.heightPixels = b.height()
            dm.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }
        return dm
    }

    private suspend fun reconnectMirror(mirror: MirrorEngine) {
        val target = target ?: return
        if (reconnecting) return
        reconnecting = true
        AppLog.i(TAG, "mirror connection lost, reconnecting")
        Session_.value = Session.Connecting(device, "Reconnecting")
        notify(notification("Reconnecting to $device…", title, false, live = true))
        val deadline = System.currentTimeMillis() + RECONNECT_MS
        while (this.mirror === mirror && System.currentTimeMillis() < deadline) {
            try {
                mirror.replaceSender(dial(target, mirror.audio))
                reconnecting = false
                notify(notification("Mirroring to $device", "Screen and sound", false, live = true))
                return
            } catch (e: Refused) {
                break
            } catch (e: Exception) {
                AppLog.i(TAG, "reconnect attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                delay(1000)
            }
        }
        reconnecting = false
        if (this.mirror === mirror) finish("Lost the connection to $device.")
    }

    /** A web link goes through the extractor; a file or a direct media link plays as it is. Null after
     *  ending the cast with the reason. */
    private suspend fun resolve(uri: Uri): PlayItem? {
        val link = link ?: return PlayItem(uri)
        Session_.value = Session.Connecting(device, "Getting the video")
        notify(notification("Getting the video…", title, false))
        return try {
            extract(link)
        } catch (e: ExtractFailed) {
            finish(e.message)
            null
        }
    }

    private suspend fun extract(link: String): PlayItem {
        val r = withContext(Dispatchers.IO) { YtDlpExtractor(this@CastService, settings.maxHeight).resolve(link) }
        r.title?.let { title = it }
        return PlayItem(Uri.parse(r.video.url), r.audio?.let { Uri.parse(it.url) }, r.video.headers, r.audio?.headers.orEmpty())
    }

    /** Dials the Show. Ends of the connection are reported as a lost link (worth a reconnect) or an
     *  end (the Show stopped it). */
    private suspend fun dial(target: Device, audio: Boolean = true): CastSender = withContext(Dispatchers.IO) {
        CastSender.connect(
            target.host, target.port, target.key, android.os.Build.MODEL, settings.scale, title,
            video = true, audio = audio, timeline = timeline,
        ) { ended ->
            // From a network thread.
            scope.launch { if (ended.lost) reconnect() else finish(ended.reason) }
        }
    }

    /** The link dropped: pause, and try for about half a minute to pick up where we were. */
    private suspend fun reconnect() {
        mirror?.let { return reconnectMirror(it) }
        val engine = engine ?: return
        val target = target ?: return
        if (reconnecting) return
        reconnecting = true
        AppLog.i(TAG, "connection lost, reconnecting")
        val wasPlaying = engine.isPlaying
        engine.pause()
        Session_.value = Session.Connecting(device, "Reconnecting")
        notify(notification("Reconnecting to $device…", title, false))
        val deadline = System.currentTimeMillis() + RECONNECT_MS
        while (this.engine === engine && System.currentTimeMillis() < deadline) {
            try {
                engine.replaceSender(dial(target))
                if (wasPlaying) engine.resume()
                reconnecting = false
                return
            } catch (e: Refused) {
                break
            } catch (e: Exception) {
                AppLog.i(TAG, "reconnect attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                delay(1000)
            }
        }
        reconnecting = false
        if (this.engine === engine) finish("Lost the connection to $device.")
    }

    /** An expired or refused stream link: get a fresh one and go on from the same second, once in a
     *  while. Returns true if a retry was started. */
    private fun retrySource(error: PlaybackException, positionMs: Long): Boolean {
        val link = link ?: return false
        val engine = engine ?: return false
        val retryable = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
        val now = System.currentTimeMillis()
        if (!retryable || now - lastRetryMs < 30_000) return false
        lastRetryMs = now
        scope.launch {
            try {
                engine.load(extract(link), positionMs)
            } catch (e: ExtractFailed) {
                finish(e.message)
            }
        }
        return true
    }

    private suspend fun connect(target: Device, item: PlayItem) {
        timeline = Timeline()
        rate = RateAdapter(settings.fpsSteps)
        link_ = LinkQuality()
        smooth = null
        AppLog.i(TAG, "connecting to ${target.name} (${target.host}:${target.port}), scale ${settings.scale}, up to ${settings.effectiveFps} fps, jpeg ${settings.effectiveQuality}")
        val sender = try {
            dial(target)
        } catch (e: Refused) {
            finish("$device refused: ${e.message}")
            return
        } catch (e: Exception) {
            finish(describeFailure(e, device))
            return
        }
        val engine = CastEngine(this, sender, timeline)
        this.engine = engine
        engine.setQuality(settings.effectiveQuality, rate.fps)
        AppLog.i(TAG, "casting to ${target.name}: $title")
        engine.onSourceError = ::retrySource
        engine.play(item) { state ->
            latest = state.copy(smooth = smooth)
            if (!reconnecting) Session_.value = Session.Casting(device, title, latest)
            state.ended?.let { finish(it) }
        }
        ticker = scope.launch {
            while (true) {
                smooth = link_.update(latest.sender?.device)
                engine.publish()
                latest.sender?.let { s ->
                    // What the Show drops counts with what the phone does: both mean too much is sent.
                    rate.update(s.videoSent, s.videoDropped + (s.device?.dropped ?: 0))
                        ?.let { engine.setQuality(engine.quality, it) }
                }
                if (!reconnecting) notify(notification(device, title, latest.playing))
                delay(1000)
            }
        }
    }

    private fun finish(reason: String?) {
        AppLog.i(TAG, "finish: ${reason ?: "stopped"}")
        stopEngine()
        Session_.value = Session.Ended(reason)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopEngine() {
        ticker?.cancel()
        ticker = null
        engine?.stop()
        engine = null
        mirror?.stop()
        mirror = null
        latest = CastState()
    }

    private fun acquireLocks() {
        if (wakeLock == null && settings.keepAwake) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "techo5cast:cast").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "techo5cast:cast").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        screenLock?.takeIf { it.isHeld }?.release()
        screenLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        stopEngine()
        releaseLocks()
        scope.cancel()
    }

    private fun notify(n: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun notification(headline: String, text: String, playing: Boolean, live: Boolean = false): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(headline)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Mirroring has nothing to pause: Stop only.
        if (live) {
            return builder.addAction(action("Stop", ACTION_STOP))
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
                .build()
        }
        val toggle = if (playing) action("Pause", ACTION_PAUSE) else action("Play", ACTION_RESUME)
        return builder.addAction(toggle)
            .addAction(action("Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun action(label: String, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, act.hashCode(), Intent(this, CastService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    companion object {
        const val ACTION_START = "dev.techo5.cast.START"
        const val ACTION_PAUSE = "dev.techo5.cast.PAUSE"
        const val ACTION_RESUME = "dev.techo5.cast.RESUME"
        const val ACTION_SEEK = "dev.techo5.cast.SEEK"
        const val ACTION_STOP = "dev.techo5.cast.STOP"
        const val ACTION_MIRROR = "dev.techo5.cast.MIRROR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION_MS = "position_ms"
        private const val CHANNEL = "casting"
        // Wi-Fi coming back plus the Show noticing its old session is dead (about 10 s of silence).
        private const val RECONNECT_MS = 30_000L
        private const val NOTIFICATION_ID = 1

        private val Session_ = MutableStateFlow<Session>(Session.Idle)
        val session = Session_.asStateFlow()

        /** The person has read why the last cast ended. */
        fun clearEnded() {
            if (Session_.value is Session.Ended) Session_.value = Session.Idle
        }

        /** Starts casting [uri] to [device]. Call from a visible activity (Android 16 background rules). */
        fun cast(context: Context, uri: Uri, device: Device, title: String?) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_START)
                .setData(uri)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_DEVICE, device.id)
                .putExtra(EXTRA_TITLE, title)
            context.startForegroundService(intent)
        }

        /** Starts mirroring the screen to [device] with the result of the system's capture consent. Call
         *  from a visible activity, right after the consent. */
        fun mirror(context: Context, resultCode: Int, data: Intent, device: Device, audio: Boolean) {
            val intent = Intent(context, CastService::class.java)
                .setAction(ACTION_MIRROR)
                .putExtra(EXTRA_DEVICE, device.id)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_AUDIO, audio)
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: String, positionMs: Long? = null) {
            val intent = Intent(context, CastService::class.java).setAction(action)
            if (positionMs != null) intent.putExtra(EXTRA_POSITION_MS, positionMs)
            context.startService(intent)
        }
    }
}

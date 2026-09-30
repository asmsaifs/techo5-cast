package dev.techo5.cast.extract

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * yt-dlp on the phone, through youtubedl-android. Format choice as `castsend` does it: up to 720p,
 * separate picture and sound where the site serves them that way.
 */
private const val TAG = "extract"

class YtDlpExtractor(context: Context) : Extractor {
    private val app = context.applicationContext

    @Synchronized
    private fun ready() {
        // Unpacks Python and yt-dlp on the first call; a no-op afterwards.
        val ytdlp = YoutubeDL.getInstance()
        ytdlp.init(app)
        // The yt-dlp shipped in the app goes stale within weeks and YouTube then answers 403; pull the
        // current release at most once a day (docs/android-app-plan.md section 9).
        val prefs = app.getSharedPreferences("extract", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0) > 24 * 3600_000L) {
            try {
                val status = ytdlp.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                Log.i(TAG, "yt-dlp update: $status, now ${ytdlp.versionName(app)}")
                prefs.edit().putLong("updated", now).apply()
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed: ${e.message}")
            }
        }
    }

    override fun resolve(url: String): Resolved {
        if (isDirectMedia(url)) return Resolved(null, Stream(url))
        try {
            ready()
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val parts = info.requestedFormats.orEmpty().filter { !it.url.isNullOrEmpty() }
            val video = parts.firstOrNull { it.vcodec != null && it.vcodec != "none" } ?: parts.firstOrNull()
            val audio = parts.firstOrNull { it !== video && (it.vcodec == null || it.vcodec == "none") }
            val title = info.title
            Log.i(TAG, "resolved ${parts.size} format(s), video=${video?.url?.take(60)} headers=${video?.httpHeaders?.keys}")
            return when {
                video != null -> Resolved(
                    title,
                    Stream(video.url!!, video.httpHeaders.orEmpty()),
                    audio?.let { Stream(it.url!!, it.httpHeaders.orEmpty()) },
                )
                !info.url.isNullOrEmpty() -> Resolved(title, Stream(info.url!!, info.httpHeaders.orEmpty()))
                else -> throw ExtractFailed("This video can't be cast.")
            }
        } catch (e: ExtractFailed) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw ExtractFailed(explain(e.message.orEmpty()), e)
        }
    }

    private fun explain(m: String): String = when {
        m.contains("DRM", true) -> "This video is protected and can't be cast."
        m.contains("Sign in", true) || m.contains("login", true) || m.contains("private", true) ||
            m.contains("members", true) || m.contains("age", true) && m.contains("confirm", true) ->
            "This video can't be cast: it needs a sign-in."
        m.contains("Unsupported URL", true) -> "This link isn't a video the app can cast."
        m.contains("Unable to download", true) || m.contains("urlopen", true) || m.contains("Network", true) ->
            "Couldn't reach the video. Check the phone's connection."
        else -> "This video can't be cast."
    }
}

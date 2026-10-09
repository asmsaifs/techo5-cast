package dev.techo5.cast.extract

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.InputStream

/**
 * yt-dlp on the phone, through youtubedl-android. Format choice as `castsend` does it: up to 720p,
 * separate picture and sound where the site serves them that way.
 */
private const val TAG = "extract"

class YtDlpExtractor(context: Context, private val maxHeight: Int = 720) : Extractor {
    private val app = context.applicationContext

    @Synchronized
    private fun ready() {
        // Unpacks Python and yt-dlp on the first call; a no-op afterwards.
        val ytdlp = YoutubeDL.getInstance()
        ytdlp.init(app)
        // The yt-dlp shipped in the app goes stale within weeks and YouTube then answers 403; pull the
        // current release at most once a day (docs/android-app-plan.md section 9).
        val prefs = app.getSharedPreferences("extract", Context.MODE_PRIVATE)
        prefs.edit().putString("version", ytdlp.versionName(app)).apply()
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated", 0) > 24 * 3600_000L) {
            try {
                val status = ytdlp.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                Log.i(TAG, "yt-dlp update: $status, now ${ytdlp.versionName(app)}")
                prefs.edit().putLong("updated", now).putString("version", ytdlp.versionName(app)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp update failed: ${e.message}")
            }
        }
    }

    companion object {
        /** The yt-dlp version last seen by a cast, or null before the first one. */
        fun knownVersion(context: Context): String? =
            context.applicationContext.getSharedPreferences("extract", Context.MODE_PRIVATE).getString("version", null)

        private fun cookiesFile(context: Context) = File(context.applicationContext.filesDir, "cookies.txt")

        /** Whether a cookies.txt has been imported; yt-dlp sends it to YouTube to get past the "not a bot" check. */
        fun hasCookies(context: Context): Boolean = cookiesFile(context).length() > 0

        /**
         * Copies a Netscape-format cookies.txt (as browser extensions export it) into the app's private
         * storage. Returns false, leaving any earlier file alone, when [source] holds no YouTube/Google cookies.
         */
        fun importCookies(context: Context, source: InputStream): Boolean =
            saveCookies(context, source.bufferedReader().use { it.readText() })

        /** Stores [text], a Netscape-format cookie file, unless it holds no YouTube/Google cookies. */
        fun saveCookies(context: Context, text: String): Boolean {
            val cookieLines = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") || it.startsWith("#HttpOnly_") }
            if (cookieLines.none { it.contains("youtube.com") || it.contains("google.com") }) return false
            cookiesFile(context).writeText(text)
            return true
        }

        fun clearCookies(context: Context) {
            cookiesFile(context).delete()
        }
    }

    override fun resolve(url: String): Resolved {
        if (isDirectMedia(url)) return Resolved(null, Stream(url))
        try {
            ready()
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                // YouTube rate-limits the web page fetch per IP and then demands a sign-in; the tv client
                // needs neither the page nor a PO token, so it carries the cast when the web client is blocked.
                addOption("--extractor-args", "youtube:player_client=default,tv")
                if (hasCookies(app)) addOption("--cookies", cookiesFile(app).absolutePath)
                addOption("-f", "bv*[height<=$maxHeight]+ba/b[height<=$maxHeight]/b")
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
            Log.w(TAG, "yt-dlp failed for $url: ${e.message}")
            throw ExtractFailed(explain(e.message.orEmpty()), e)
        }
    }

    private fun explain(m: String): String = when {
        m.contains("DRM", true) -> "This video is protected and can't be cast."
        m.contains("not a bot", true) ->
            "YouTube is blocking this connection. Import your YouTube cookies in Settings, or try again later."
        m.contains("Sign in", true) || m.contains("login", true) || m.contains("private", true) ||
            m.contains("members", true) || m.contains("age", true) && m.contains("confirm", true) ->
            "This video can't be cast: it needs a sign-in."
        m.contains("Unsupported URL", true) -> "This link isn't a video the app can cast."
        m.contains("Unable to download", true) || m.contains("urlopen", true) || m.contains("Network", true) ->
            "Couldn't reach the video. Check the phone's connection."
        else -> "This video can't be cast."
    }
}

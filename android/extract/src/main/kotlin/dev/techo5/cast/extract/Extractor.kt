package dev.techo5.cast.extract

/** One stream to fetch, with the headers its server wants. */
data class Stream(val url: String, val headers: Map<String, String> = emptyMap())

/** A link turned into what the player needs: a picture stream and, if separate, a sound stream. */
data class Resolved(val title: String?, val video: Stream, val audio: Stream? = null)

/** The link can't be played; [message] is a sentence for the person. */
class ExtractFailed(reason: String, cause: Throwable? = null) : Exception(reason, cause)

/** Turns a page link into streams. Behind an interface so yt-dlp could be swapped (plan section 9). */
interface Extractor {
    /** Call off the main thread; takes a few seconds. */
    fun resolve(url: String): Resolved
}

private val URL = Regex("""https?://[^\s<>"']+""")

/** The first web link in shared text ("Watch this: https://youtu.be/..."). */
fun findUrl(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

private val DIRECT = Regex("""\.(mp4|m4v|mkv|webm|mov|m3u8|mpd|mp3|m4a|aac|ogg|opus|flac|wav)(\?.*)?$""", RegexOption.IGNORE_CASE)

/** True for a link that is a media file or playlist itself and needs no extractor. */
fun isDirectMedia(url: String): Boolean = DIRECT.containsMatchIn(url.substringBefore('#'))

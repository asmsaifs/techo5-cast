package dev.techo5.cast.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** The last events of this run, for "Copy log" in the settings: enough to say what went wrong. */
object AppLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${time.format(Date())} $tag: $message")
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

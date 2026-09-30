package dev.techo5.cast.app

import android.content.Context

/** What the person can tune (docs/android-app-plan.md section 10). Read when a cast starts. */
data class Settings(
    val fps: Int = 30,
    val halfSize: Boolean = true,
    val jpegQuality: Int = 80,
    val maxHeight: Int = 720,
    val batterySaver: Boolean = false,
    val keepAwake: Boolean = true,
) {
    /** Battery saver trades picture for power: at most 15 fps and a lower JPEG quality. */
    val effectiveFps: Int get() = if (batterySaver) minOf(fps, 15) else fps
    val effectiveQuality: Int get() = if (batterySaver) minOf(jpegQuality, 65) else jpegQuality
    val scale: Int get() = if (halfSize) 2 else 1

    /** The frame-rate steps to adapt within: the chosen rate and the lower ones. */
    val fpsSteps: List<Int> get() = listOf(30, 24, 15).filter { it <= effectiveFps }.ifEmpty { listOf(15) }

    companion object {
        private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun load(context: Context): Settings {
            val p = prefs(context)
            val d = Settings()
            return Settings(
                fps = p.getInt("fps", d.fps),
                halfSize = p.getBoolean("halfSize", d.halfSize),
                jpegQuality = p.getInt("jpegQuality", d.jpegQuality),
                maxHeight = p.getInt("maxHeight", d.maxHeight),
                batterySaver = p.getBoolean("batterySaver", d.batterySaver),
                keepAwake = p.getBoolean("keepAwake", d.keepAwake),
            )
        }

        fun save(context: Context, s: Settings) {
            prefs(context).edit()
                .putInt("fps", s.fps).putBoolean("halfSize", s.halfSize).putInt("jpegQuality", s.jpegQuality)
                .putInt("maxHeight", s.maxHeight).putBoolean("batterySaver", s.batterySaver)
                .putBoolean("keepAwake", s.keepAwake).apply()
        }
    }
}

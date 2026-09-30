package dev.techo5.cast.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.techo5.cast.discovery.Device

/** Saved Shows as share-sheet targets: a share to one of them skips the picker. */
object Shortcuts {
    private const val CATEGORY = "dev.techo5.cast.category.SHOW"

    fun publish(context: Context, devices: List<Device>) {
        val shortcuts = devices.map { d ->
            ShortcutInfoCompat.Builder(context, d.id)
                .setShortLabel(d.name)
                .setLongLabel("Cast to ${d.name}")
                .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_media_play))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                .setCategories(setOf(CATEGORY))
                .setLongLived(true)
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // Sharing shortcuts are a convenience; the picker still works without them.
        }
    }
}

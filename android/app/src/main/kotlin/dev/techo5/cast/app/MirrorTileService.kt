package dev.techo5.cast.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile: tap to mirror the screen to the last used Show, tap again to stop. The capture
 * consent needs an activity, so a tap opens [MirrorActivity] over whatever is on screen.
 */
class MirrorTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val session = CastService.session.value
        if (session is Session.Casting && session.state.live) {
            CastService.send(this, CastService.ACTION_STOP)
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
            return
        }
        val intent = Intent(this, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val session = CastService.session.value
        tile.state = if (session is Session.Casting && session.state.live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

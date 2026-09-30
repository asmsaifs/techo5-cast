package dev.techo5.cast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.DeviceStore

/**
 * The way into mirror mode, from the button in the app and from the quick-settings tile: a transparent
 * hop that picks the Show (the one in [EXTRA_DEVICE], else the last used, else the first saved), asks
 * for the sound and notification permissions once, shows the system's screen-capture consent, and
 * hands the result to [CastService]. The service has to be started from here, a visible activity, right
 * after the consent (docs/android-app-plan.md 2, Android 16 notes).
 */
class MirrorActivity : ComponentActivity() {
    private var device: Device? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val target = device
        if (result.resultCode == RESULT_OK && data != null && target != null) {
            DeviceStore(this).lastUsed = target.id
            val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            CastService.mirror(this, result.resultCode, data, target, audio)
        }
        finish()
    }

    // Denied is fine: without the sound permission the mirror is picture only, and without the
    // notification one the cast runs with its notification hidden.
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = DeviceStore(this)
        val saved = store.all()
        device = intent.getStringExtra(CastService.EXTRA_DEVICE)?.let { store.find(it) }
            ?: store.lastUsed?.let { store.find(it) }
            ?: saved.firstOrNull()
        if (device == null) {
            Toast.makeText(this, "Add a Show first, then mirror to it.", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) askConsent() else permissions.launch(wanted.toTypedArray())
    }

    private fun askConsent() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(manager.createScreenCaptureIntent())
    }
}

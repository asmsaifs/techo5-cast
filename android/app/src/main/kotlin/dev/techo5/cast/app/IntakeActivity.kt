package dev.techo5.cast.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.DeviceStore
import dev.techo5.cast.extract.findUrl

/**
 * Where a share or "open with" lands (docs/android-app-plan.md 3.1, 3.2). It never shows a screen of
 * its own: it works out what was shared, picks the Show (asking only when there is more than one),
 * starts [CastService] and finishes, so the app that shared is still in front.
 */
class IntakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = sharedSource(intent)
        if (source == null) {
            toast("There is no video in that.")
            finish()
            return
        }
        val store = DeviceStore(this)
        val devices = store.all().sortedByDescending { it.id == store.lastUsed }
        when {
            devices.isEmpty() -> {
                toast("Add a Show first.")
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            devices.size == 1 -> cast(store, devices[0], source)
            else -> AlertDialog.Builder(this)
                .setTitle("Cast to")
                .setItems(devices.map { it.name }.toTypedArray()) { _, i -> cast(store, devices[i], source) }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun cast(store: DeviceStore, device: Device, source: Uri) {
        store.lastUsed = device.id
        CastService.cast(this, source, device, null)
        toast("Casting to ${device.name}")
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun sharedSource(i: Intent): Uri? = when (i.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: i.getStringExtra(Intent.EXTRA_TEXT)?.let(::findUrl)?.let(Uri::parse)
        Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
        else -> null
    }
}

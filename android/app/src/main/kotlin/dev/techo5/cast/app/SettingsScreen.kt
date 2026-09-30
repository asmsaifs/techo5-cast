package dev.techo5.cast.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(Settings.load(context)) }
    fun update(next: Settings) { s = next; Settings.save(context, next) }

    Column(
        Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Applies to the next cast.", style = MaterialTheme.typography.bodySmall)

        Section("Frame rate") {
            Chips(listOf(30, 24, 15), s.fps, { "$it fps" }) { update(s.copy(fps = it)) }
        }
        Section("Picture size") {
            Chips(listOf(true, false), s.halfSize, { if (it) "Half (smoother)" else "Full" }) { update(s.copy(halfSize = it)) }
        }
        Section("JPEG quality: ${s.jpegQuality}") {
            Slider(
                value = s.jpegQuality.toFloat(), valueRange = 50f..95f, steps = 8,
                onValueChange = { update(s.copy(jpegQuality = it.toInt())) },
            )
        }
        Section("Highest video quality to fetch") {
            Chips(listOf(480, 720, 1080), s.maxHeight, { "${it}p" }) { update(s.copy(maxHeight = it)) }
        }
        Toggle("Battery saver", "At most 15 fps and lower quality", s.batterySaver) { update(s.copy(batterySaver = it)) }
        Toggle("Keep the phone awake", "Holds it from sleeping while casting; needed for long videos on some phones", s.keepAwake) {
            update(s.copy(keepAwake = it))
        }

        Section("Diagnostics") {
            OutlinedButton(onClick = {
                val text = AppLog.text().ifEmpty { "(nothing logged yet)" }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TECHO5 Cast log", text))
                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy log") }
        }

        Section("About") {
            Text(
                "TECHO5 Cast is free software under the GNU GPL v3. It sends video to a TECHO5 Echo Show " +
                    "over the protocol in the techo5-cast repository (MIT).\n\n" +
                    "Made with: Media3 / ExoPlayer (Apache 2.0), youtubedl-android (GPL 3.0), yt-dlp " +
                    "(Unlicense), noise-java (MIT), Jetpack Compose (Apache 2.0).\n\n" +
                    "Not for protected video (Netflix, Prime, Disney+ and the like). Fetching YouTube streams " +
                    "outside YouTube's own player is against YouTube's terms; that is why this app is not on " +
                    "Google Play. Use it for video you may watch.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

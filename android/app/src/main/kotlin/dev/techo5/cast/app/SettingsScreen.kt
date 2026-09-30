package dev.techo5.cast.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.techo5.cast.app.ui.Divider
import dev.techo5.cast.app.ui.Panel
import dev.techo5.cast.app.ui.SectionLabel
import dev.techo5.cast.extract.YtDlpExtractor

/** Settings (docs/android-app-plan.md section 10), grouped as in the Stitch design. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(Settings.load(context)) }
    fun update(next: Settings) { s = next; Settings.save(context, next) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Applies from the next cast.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Group("Picture") {
                Setting("Frame rate") {
                    Choice(listOf(30, 24, 15), s.fps, { "$it fps" }) { update(s.copy(fps = it)) }
                }
                Divider()
                Setting("Picture size") {
                    Choice(listOf(true, false), s.halfSize, { if (it) "Half (smoother)" else "Full" }) { update(s.copy(halfSize = it)) }
                }
                Divider()
                Setting("JPEG quality", value = s.jpegQuality.toString()) {
                    Slider(
                        value = s.jpegQuality.toFloat(), valueRange = 50f..95f, steps = 8,
                        onValueChange = { update(s.copy(jpegQuality = it.toInt())) },
                    )
                }
                Divider()
                Setting("Highest video quality to fetch") {
                    Choice(listOf(480, 720, 1080), s.maxHeight, { "${it}p" }) { update(s.copy(maxHeight = it)) }
                }
            }

            Group("Power") {
                Toggle("Battery saver", "At most 15 fps and lower quality", s.batterySaver) { update(s.copy(batterySaver = it)) }
                Divider()
                Toggle("Keep playing with the screen off", "Recommended for long videos. Mirroring always keeps the screen on.", s.keepAwake) { update(s.copy(keepAwake = it)) }
            }

            Group("Diagnostics") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Copy log", style = MaterialTheme.typography.titleSmall)
                        Text("Copies recent events to the clipboard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        val text = AppLog.text().ifEmpty { "(nothing logged yet)" }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TECHO5 Cast log", text))
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.ContentCopy, "Copy log", tint = MaterialTheme.colorScheme.primary) }
                }
                Divider()
                Setting("yt-dlp version", value = YtDlpExtractor.knownVersion(context) ?: "not fetched yet", hint = "Updates itself daily")
            }

            Group("About") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_logo_mark), null, Modifier.size(36.dp), tint = Color.Unspecified)
                        Column {
                            Text("TECHO5 Cast", style = MaterialTheme.typography.titleMedium)
                            Text("Version ${versionName(context)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "Free software under the GNU GPL v3. Not for protected video (Netflix, Prime, Disney+ and the like). " +
                            "Fetching YouTube streams outside YouTube's own player is against YouTube's terms, so this app is not on Google Play.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Divider()
                var licences by remember { mutableStateOf(false) }
                TextButton(onClick = { licences = !licences }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(if (licences) "Hide licences" else "Licences", Modifier.weight(1f))
                }
                if (licences) {
                    Text(
                        "TECHO5 Cast: GPL 3.0\nMedia3 / ExoPlayer: Apache 2.0\nzxing-android-embedded: Apache 2.0\nyoutubedl-android: GPL 3.0\nyt-dlp: Unlicense\nnoise-java: MIT\n" +
                            "Jetpack Compose and AndroidX: Apache 2.0\nManrope and Inter fonts: SIL Open Font License 1.1\n" +
                            "The protocol and castsend: MIT",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun versionName(context: Context): String =
    try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Panel { Column { content() } }
    }
}

/** A titled row: the control goes below the title; [value] is shown at the right of it. */
@Composable
private fun Setting(title: String, value: String? = null, hint: String? = null, control: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        control()
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onPick(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

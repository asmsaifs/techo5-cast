package dev.techo5.cast.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.techo5.cast.app.ui.CastTheme
import dev.techo5.cast.app.ui.Panel
import dev.techo5.cast.app.ui.SectionLabel
import dev.techo5.cast.app.ui.StatusPill
import dev.techo5.cast.app.ui.Tone
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.DeviceStore
import dev.techo5.cast.discovery.Found
import dev.techo5.cast.discovery.discover
import dev.techo5.cast.extract.findUrl

/**
 * The one screen (docs/android-app-plan.md 3.3, 10): the cast in progress, the Shows saved and nearby,
 * and the ways to start one. The casting itself runs in [CastService], so leaving the app does not
 * stop it. Layout and tokens follow the Stitch design "TECHO5 Cast".
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CastTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var settingsOpen by remember { mutableStateOf(false) }
                    BackHandler(enabled = settingsOpen) { settingsOpen = false }
                    if (settingsOpen) SettingsScreen { settingsOpen = false } else Home { settingsOpen = true }
                }
            }
        }
    }
}

/** What the add sheet starts from: a Show found on the network, or nothing (typed by hand). */
private class AddTarget(val found: Found?)

@Composable
private fun Home(openSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore(context) }
    var saved by remember { mutableStateOf(store.all()) }
    var nearby by remember { mutableStateOf<List<Found>>(emptyList()) }
    var selected by remember {
        mutableStateOf(store.lastUsed?.takeIf { id -> saved.any { it.id == id } } ?: saved.firstOrNull()?.id)
    }
    var adding by remember { mutableStateOf<AddTarget?>(null) }
    var pasting by remember { mutableStateOf(false) }
    val session by CastService.session.collectAsState()

    LaunchedEffect(Unit) { discover(context).collect { nearby = it } }
    LaunchedEffect(Unit) { Shortcuts.publish(context, saved) }

    fun refresh() {
        saved = store.all()
        Shortcuts.publish(context, saved)
        if (saved.none { it.id == selected }) selected = saved.firstOrNull()?.id
    }

    fun castTo(uri: Uri, title: String?) {
        val device = saved.firstOrNull { it.id == selected } ?: return
        store.lastUsed = device.id
        CastService.cast(context, uri, device, title)
    }

    // The permission is asked at the first cast; without it the cast still runs, the notification is
    // just hidden, so the result is not waited for.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) castTo(uri, displayName(context, uri))
    }

    val canCast = selected != null && session !is Session.Connecting

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TopBar(openSettings)

        SessionCard(session)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Your Shows") {
                if (saved.isNotEmpty()) {
                    Text("${saved.size} saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (saved.isEmpty()) GettingStarted { adding = AddTarget(null) }
            for (d in saved) {
                val name = (session as? Session.Casting)?.device ?: (session as? Session.Connecting)?.device
                DeviceCard(
                    device = d,
                    selected = selected == d.id,
                    casting = name == d.name,
                    seen = nearby.any { it.host == d.host },
                    onSelect = { selected = d.id },
                    onRemove = { store.remove(d.id); refresh() },
                )
            }
        }

        val unsaved = nearby.filter { f -> saved.none { it.host == f.host } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Found nearby") {
                Text("Scanning", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (unsaved.isEmpty() && saved.isEmpty()) {
                Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No Shows found yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Check that Cast is on and the phone is on the same Wi-Fi. If your network blocks discovery, add the Show by its address.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { adding = AddTarget(null) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Add by address")
                        }
                    }
                }
            } else if (unsaved.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "No other Shows nearby.",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { adding = AddTarget(null) }) { Text("Add by address") }
                }
            }
            for (f in unsaved) NearbyRow(f) { adding = AddTarget(f) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = canCast,
                    onClick = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        picker.launch(arrayOf("video/*", "audio/*"))
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.VideoFile, null, Modifier.size(20.dp))
                    Text("  Cast a file")
                }
                OutlinedButton(
                    enabled = canCast,
                    onClick = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        pasting = true
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.Link, null, Modifier.size(20.dp))
                    Text("  Paste a link")
                }
            }
            FilledTonalButton(
                enabled = canCast,
                onClick = {
                    // Sound and notification permissions, and the screen-capture consent, are asked there.
                    context.startActivity(
                        Intent(context, MirrorActivity::class.java).putExtra(CastService.EXTRA_DEVICE, selected),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.ScreenShare, null, Modifier.size(20.dp))
                Text("  Mirror screen")
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Share, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (saved.isEmpty()) "  Add a Show to start casting." else "  Or share a video from YouTube or your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    adding?.let { target ->
        AddDeviceSheet(
            found = target.found,
            onSave = { store.save(it); selected = it.id; adding = null; refresh() },
            onDismiss = { adding = null },
        )
    }
    if (pasting) {
        PasteLinkDialog(
            onCast = { url -> pasting = false; castTo(Uri.parse(url), null) },
            onDismiss = { pasting = false },
        )
    }
}

@Composable
private fun TopBar(openSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_logo_mark), null, Modifier.size(32.dp), tint = Color.Unspecified)
        Text(
            "TECHO5 Cast",
            Modifier.weight(1f).padding(start = 10.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(onClick = openSettings) {
            Icon(Icons.Filled.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionCard(session: Session) {
    val context = LocalContext.current
    when (session) {
        Session.Idle -> {}
        is Session.Connecting -> Panel(highlighted = true) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Column {
                    Text(session.step + "…", style = MaterialTheme.typography.titleSmall)
                    Text(session.device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is Session.Ended -> session.reason?.let { reason ->
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill("Cast ended", Tone.Error)
                    Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = CastService::clearEnded, contentPadding = PaddingValues(0.dp)) { Text("Dismiss") }
                }
            }
        }
        is Session.Casting -> Panel(highlighted = true) {
            val st = session.state
            var dragging by remember { mutableStateOf<Float?>(null) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // The picture is on the Show; this tile stands in for it.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerLow),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_logo_mark), null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    )
                    StatusPill(
                        if (st.playing) "Casting" else "Paused",
                        if (st.playing) Tone.Live else Tone.Neutral,
                        Modifier.align(Alignment.TopStart).padding(10.dp),
                    )
                }
                Column {
                    Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Filled.Tv, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("  " + session.device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (st.durationMs > 0) {
                    Column {
                        Slider(
                            value = dragging ?: (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                dragging?.let { CastService.send(context, CastService.ACTION_SEEK, (it * st.durationMs).toLong()) }
                                dragging = null
                            },
                            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            val shown = dragging?.let { (it * st.durationMs).toLong() } ?: st.positionMs
                            Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(clock(st.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (st.live) {
                    // Mirroring has no position to seek or pause: only Stop.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FilledTonalButton(onClick = { CastService.send(context, CastService.ACTION_STOP) }) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Text("  Stop mirroring")
                        }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { CastService.send(context, CastService.ACTION_SEEK, (st.positionMs - 10_000).coerceAtLeast(0)) },
                        Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Replay10, "Back 10 seconds") }
                    IconButton(onClick = { CastService.send(context, CastService.ACTION_STOP) }, Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Stop, "Stop casting", tint = MaterialTheme.colorScheme.error)
                    }
                    FilledIconButton(
                        onClick = { CastService.send(context, if (st.playing) CastService.ACTION_PAUSE else CastService.ACTION_RESUME) },
                        Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (st.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (st.playing) "Pause" else "Play",
                            Modifier.size(32.dp),
                        )
                    }
                    IconButton(
                        onClick = { CastService.send(context, CastService.ACTION_SEEK, st.positionMs + 10_000) },
                        Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Forward10, "Forward 10 seconds") }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    selected: Boolean,
    casting: Boolean,
    seen: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Panel(highlighted = selected) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Box(
                Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Tv, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                casting -> StatusPill("Casting", Tone.Live)
                seen -> StatusPill("Ready", Tone.Neutral)
                else -> StatusPill("Not seen", Tone.Warning)
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(found: Found, onAdd: () -> Unit) {
    Panel {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Tv, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(found.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(found.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Text(" Add")
            }
        }
    }
}

@Composable
private fun GettingStarted(onAdd: () -> Unit) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Get started", style = MaterialTheme.typography.titleMedium)
            Step(1, "On the Show, turn Cast on and set its key (cast_key in Home Assistant).")
            Step(2, "Add the Show here with that key. Keep the phone on the same Wi-Fi.")
            Step(3, "In YouTube or any video app, tap Share and pick TECHO5 Cast. Or use Cast a file.")
            FilledTonalButton(onClick = onAdd, Modifier.padding(top = 4.dp)) { Text("Add a Show") }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PasteLinkDialog(onCast: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // If a link is on the clipboard already, start from it.
    var text by remember {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        mutableStateOf(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let(::findUrl) ?: "")
    }
    val url = findUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a link") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Video link") },
                placeholder = { Text("https://…") },
                isError = text.isNotBlank() && url == null,
                supportingText = { if (text.isNotBlank() && url == null) Text("That doesn't look like a web link") },
            )
        },
        confirmButton = { TextButton(enabled = url != null, onClick = { onCast(url!!) }) { Text("Cast") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

internal fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

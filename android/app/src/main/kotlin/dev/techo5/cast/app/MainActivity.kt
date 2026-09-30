package dev.techo5.cast.app

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.DeviceStore
import dev.techo5.cast.discovery.Found
import dev.techo5.cast.discovery.discover

/**
 * The one screen (docs/android-app-plan.md 3.3, 10): the Shows saved and nearby, a way to add one with
 * its key, "Cast a file", and the cast in progress with its controls. The casting itself runs in
 * [CastService], so leaving the app does not stop it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var settingsOpen by remember { mutableStateOf(false) }
                    BackHandler(enabled = settingsOpen) { settingsOpen = false }
                    if (settingsOpen) SettingsScreen { settingsOpen = false } else Home { settingsOpen = true }
                }
            }
        }
    }
}

@Composable
private fun Home(openSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DeviceStore(context) }
    var saved by remember { mutableStateOf(store.all()) }
    var nearby by remember { mutableStateOf<List<Found>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(saved.firstOrNull()?.id) }
    // A Show to add: its address is filled in, the key is what's missing.
    var adding by remember { mutableStateOf<Found?>(null) }
    var addingManually by remember { mutableStateOf(false) }
    val session by CastService.session.collectAsState()

    LaunchedEffect(Unit) {
        discover(context).collect { nearby = it }
    }
    LaunchedEffect(Unit) { Shortcuts.publish(context, saved) }

    fun refresh() {
        saved = store.all()
        Shortcuts.publish(context, saved)
        if (saved.none { it.id == selected }) selected = saved.firstOrNull()?.id
    }

    // The permission is asked at the first cast; without it the cast still runs, the notification is
    // just hidden, so the result is not waited for.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val device = saved.firstOrNull { it.id == selected }
        if (uri != null && device != null) {
            CastService.cast(context, uri, device, displayName(context, uri))
        }
    }

    Column(
        Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TECHO5 Cast", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = openSettings) { Text("Settings") }
        }

        NowCasting(session)

        Text("Your Shows", style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Getting started", style = MaterialTheme.typography.titleSmall)
                Text("1. On the Show, turn Cast on and set its key (cast_key in Home Assistant).")
                Text("2. Add the Show below with that key. Keep the phone on the same Wi-Fi.")
                Text("3. In YouTube or any video app, tap Share and pick TECHO5 Cast. Or tap Cast a file.")
            }
        }
        for (d in saved) {
            val here = nearby.any { it.host == d.host }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text("${d.host}:${d.port}" + if (here) " · nearby" else "")
                    }
                    Row {
                        TextButton(onClick = { selected = d.id }) { Text(if (selected == d.id) "Selected" else "Select") }
                        TextButton(onClick = { store.remove(d.id); refresh() }) { Text("Remove") }
                    }
                }
            }
        }

        val unsaved = nearby.filter { f -> saved.none { it.host == f.host } }
        if (unsaved.isNotEmpty()) {
            Text("Found nearby", style = MaterialTheme.typography.titleMedium)
            for (f in unsaved) {
                OutlinedButton(onClick = { adding = f; addingManually = false }, Modifier.fillMaxWidth()) {
                    Text("${f.name} (${f.host})")
                }
            }
        }
        TextButton(onClick = { addingManually = true; adding = null }) { Text("Add a Show by address") }

        if (adding != null || addingManually) {
            AddDevice(
                found = adding,
                onSave = { store.save(it); selected = it.id; adding = null; addingManually = false; refresh() },
                onCancel = { adding = null; addingManually = false },
            )
        }

        Button(
            enabled = selected != null && session !is Session.Connecting,
            onClick = {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                picker.launch(arrayOf("video/*", "audio/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cast a file") }
    }
}

@Composable
private fun NowCasting(session: Session) {
    val context = LocalContext.current
    when (session) {
        Session.Idle -> {}
        is Session.Connecting -> Card(Modifier.fillMaxWidth()) {
            Text("${session.step} · ${session.device}…", Modifier.padding(16.dp))
        }
        is Session.Ended -> session.reason?.let {
            Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        }
        is Session.Casting -> Card(Modifier.fillMaxWidth()) {
            val st = session.state
            var dragging by remember { mutableStateOf<Float?>(null) }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Casting to ${session.device}", style = MaterialTheme.typography.labelLarge)
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                if (st.durationMs > 0) {
                    Slider(
                        value = dragging ?: (st.positionMs.toFloat() / st.durationMs),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { CastService.send(context, CastService.ACTION_SEEK, (it * st.durationMs).toLong()) }
                            dragging = null
                        },
                    )
                    Text("${clock(st.positionMs)} / ${clock(st.durationMs)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        CastService.send(context, if (st.playing) CastService.ACTION_PAUSE else CastService.ACTION_RESUME)
                    }) { Text(if (st.playing) "Pause" else "Play") }
                    OutlinedButton(onClick = { CastService.send(context, CastService.ACTION_STOP) }) { Text("Stop") }
                }
            }
        }
    }
}

@Composable
private fun AddDevice(found: Found?, onSave: (Device) -> Unit, onCancel: () -> Unit) {
    var name by remember(found) { mutableStateOf(found?.name ?: "") }
    var address by remember(found) { mutableStateOf(found?.let { "${it.host}:${it.port}" } ?: "") }
    var key by remember(found) { mutableStateOf("") }
    val parsed = parseAddress(address)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                address, { address = it }, label = { Text("Address (host:port)") }, singleLine = true,
                isError = address.isNotEmpty() && parsed == null, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(key, { key = it }, label = { Text("Cast key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = parsed != null && key.isNotBlank(),
                    onClick = { onSave(Device(name.ifBlank { parsed!!.first }, parsed!!.first, parsed.second, key.trim())) },
                ) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** "host:port", with the port optional (8940 by default). */
private fun parseAddress(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val i = t.lastIndexOf(':')
    if (i < 0) return t to 8940
    val port = t.substring(i + 1).toIntOrNull() ?: return null
    return if (i > 0 && port in 1..65535) t.substring(0, i) to port else null
}

private fun displayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

package dev.techo5.cast.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.techo5.cast.discovery.Device
import dev.techo5.cast.discovery.Found
import dev.techo5.cast.engine.CastSender
import dev.techo5.cast.engine.CastSender.Companion.Refused
import dev.techo5.cast.engine.Timeline
import dev.techo5.cast.protocol.parsePairing
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Check {
    data object Idle : Check
    data object Running : Check
    data class Done(val ok: Boolean, val message: String) : Check
}

/** Adds a Show: name, address and Cast key, with a connection check so a wrong key shows up here and
 *  not on the first cast (docs/android-app-plan.md section 10, "Add a device"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceSheet(found: Found?, onSave: (Device) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(found?.name ?: "") }
    var address by remember { mutableStateOf(found?.let { "${it.host}:${it.port}" } ?: "") }
    var key by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<Check>(Check.Idle) }
    var scanned by remember { mutableStateOf<String?>(null) }
    // The Show's pairing code (Settings > Connections > Pairing code) holds all three fields.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val pairing = parsePairing(text)
        if (pairing == null) {
            scanned = "That is not a TECHO5 Cast code."
        } else {
            if (pairing.name.isNotBlank()) name = pairing.name
            address = "${pairing.host}:${pairing.port}"
            key = pairing.key
            check = Check.Idle
            scanned = "Got ${pairing.name.ifBlank { pairing.host }}. Check the connection, then save."
        }
    }
    val scope = rememberCoroutineScope()
    val parsed = parseAddress(address)
    val device = parsed?.takeIf { key.isNotBlank() }?.let { Device(name.ifBlank { it.first }, it.first, it.second, key.trim()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (found != null) "Add ${found.name}" else "Add a Show", style = MaterialTheme.typography.titleLarge)
            Text(
                "Scan the code on the Show (Settings, Connections, Pairing code), or type the address and " +
                    "Cast key. The key is stored encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point at the code on the Show")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(20.dp))
                Text("  Scan the code")
            }
            scanned?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(name, { name = it; check = Check.Idle }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(
                address, { address = it; check = Check.Idle }, Modifier.fillMaxWidth(),
                label = { Text("Address") }, placeholder = { Text("192.168.1.181:8940") }, singleLine = true,
                isError = address.isNotEmpty() && parsed == null,
                supportingText = { if (address.isNotEmpty() && parsed == null) Text("Use host or host:port") },
            )
            OutlinedTextField(
                key, { key = it; check = Check.Idle }, Modifier.fillMaxWidth(),
                label = { Text("Cast key") }, singleLine = true,
            )

            when (val c = check) {
                Check.Idle -> {}
                Check.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                }
                is Check.Done -> Text(
                    c.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = device != null && check != Check.Running,
                    onClick = {
                        val d = device ?: return@OutlinedButton
                        check = Check.Running
                        scope.launch { check = test(d) }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Check connection") }
                Button(
                    enabled = device != null,
                    onClick = { device?.let(onSave) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Save") }
            }
        }
    }
}

/** Dials the Show and hangs up. */
private suspend fun test(device: Device): Check = withContext(Dispatchers.IO) {
    try {
        CastSender.connect(
            device.host, device.port, device.key, android.os.Build.MODEL, 2,
            video = true, audio = true, timeline = Timeline(),
        ) {}.close()
        Check.Done(true, "Connected to ${device.name}.")
    } catch (e: Refused) {
        Check.Done(false, "${device.name} said no: ${e.message}")
    } catch (e: Exception) {
        Check.Done(false, describeFailure(e, device.name))
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

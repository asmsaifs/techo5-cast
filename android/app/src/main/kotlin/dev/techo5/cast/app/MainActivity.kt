package dev.techo5.cast.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.techo5.cast.protocol.Hello
import dev.techo5.cast.protocol.Kind
import dev.techo5.cast.protocol.dial
import dev.techo5.cast.protocol.parseWelcome
import dev.techo5.cast.protocol.readMessage
import dev.techo5.cast.protocol.stamp
import dev.techo5.cast.protocol.toJson
import dev.techo5.cast.protocol.writeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import kotlin.random.Random

/**
 * The M0 spike's only screen: dial a device, send a hello and a made-up frame and audio chunk, show
 * what came back. This is here to prove the protocol module — the Noise handshake and message
 * framing in particular — on Android's own runtime and network stack, not just a desktop JVM (that
 * proof is [dev.techo5.cast.protocol.RealDeviceSmokeTest], which this screen mirrors). Nothing here
 * is the app described in `docs/android-app-plan.md`; the engine (ExoPlayer capture, the timeline,
 * mirroring) comes next.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    var addr by remember { mutableStateOf("192.168.1.181:8940") }
    var key by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("Enter the device's address and Cast key, then Test.") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TECHO5 Cast — protocol spike", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text("Device address (host:port)") })
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Cast key") })
                Button(enabled = !running, onClick = {
                    running = true
                    log = "Connecting…"
                    scope.launch {
                        log = try {
                            withContext(Dispatchers.IO) { testCast(addr, key) }
                        } catch (e: Exception) {
                            "Failed: ${e.javaClass.simpleName}: ${e.message}"
                        }
                        running = false
                    }
                }) { Text(if (running) "Testing…" else "Test") }
                Text(log)
            }
        }
    }
}

private fun testCast(addr: String, key: String): String {
    val (host, portStr) = addr.split(":")
    val out = StringBuilder()
    Socket(host, portStr.trim().toInt()).use { socket ->
        socket.tcpNoDelay = true
        val start = System.nanoTime()
        val secure = dial(socket, key)
        out.appendLine("handshake: ok, in ${(System.nanoTime() - start) / 1_000_000} ms")

        val hello = Hello(name = "Nothing Phone (2)", video = true, audio = true, rate = 48000, channels = 2, scale = 2)
        secure.writeMessage(Kind.HELLO, hello.toJson().toByteArray(Charsets.UTF_8))
        val welcomeMsg = secure.readMessage()
        val welcome = parseWelcome(String(welcomeMsg.payload, Charsets.UTF_8))
        out.appendLine("welcome: ok=${welcome.ok} ${welcome.w}x${welcome.h} rate=${welcome.rate} latency=${welcome.latencyMs}ms")
        if (!welcome.ok) {
            out.appendLine("reason: ${welcome.reason}")
            return out.toString()
        }

        val t0 = System.nanoTime()
        fun us() = (System.nanoTime() - t0) / 1000
        secure.writeMessage(Kind.CLOCK, stamp(us()))

        // A small solid-color JPEG stand-in for a real frame (the encoder comes with the engine module).
        val jpeg = solidJpeg(welcome.w / 2, welcome.h / 2)
        secure.writeMessage(Kind.VIDEO, stamp(us()), jpeg)
        out.appendLine("sent one frame (${jpeg.size} bytes)")

        val pcm = ByteArray(48000 / 4 * 4) // a quarter second of silence, 48kHz stereo S16LE
        secure.writeMessage(Kind.AUDIO, stamp(us()), pcm)
        out.appendLine("sent 250 ms of audio")

        Thread.sleep(300)
        secure.writeMessage(Kind.BYE)
        out.appendLine("done — check the device log for cast decoded_fps / audio_late")
    }
    return out.toString()
}

/** A JPEG the device can decode, without pulling in the app's real capture pipeline yet: a solid
 *  colour bitmap, encoded through Android's own [android.graphics.Bitmap]. */
private fun solidJpeg(w: Int, h: Int): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
    bmp.eraseColor(android.graphics.Color.rgb(0x30 + Random.nextInt(0x20), 0x70, 0xC0))
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    return out.toByteArray()
}

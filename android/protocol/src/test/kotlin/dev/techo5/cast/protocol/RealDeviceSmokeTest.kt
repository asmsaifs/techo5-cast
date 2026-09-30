package dev.techo5.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Sends one frame and a second of audio to a real Show, straight from the Kotlin handshake and
 * message framing — not the Go `interop` stand-in [WireTest] uses. Skipped unless `-DrealDevice` and
 * `-DrealKey` are given: it needs a Show on the network with Cast switched on, which CI does not have.
 *
 *	./gradlew :protocol:test --tests "*RealDeviceSmokeTest*" -DrealDevice=192.168.1.181:8940 -DrealKey=<key>
 */
class RealDeviceSmokeTest {
    @Test
    fun `a Kotlin-only hello, frame and audio chunk reach a real device`() {
        val addr = System.getProperty("realDevice")
        val key = System.getProperty("realKey")
        assumeTrue("set -DrealDevice=host:port -DrealKey=... to run this against a real Show", addr != null && key != null)

        val (host, portStr) = addr!!.split(":")
        Socket(host, portStr.toInt()).use { socket ->
            socket.tcpNoDelay = true
            val secure = dial(socket, key!!)

            val hello = Hello(name = "kotlin-smoke-test", video = true, audio = true, rate = 48000, channels = 2, scale = 2)
            secure.writeMessage(Kind.HELLO, hello.toJson().toByteArray(Charsets.UTF_8))
            val welcomeMsg = secure.readMessage()
            assertEquals(Kind.WELCOME, welcomeMsg.kind)
            val welcome = parseWelcome(String(welcomeMsg.payload, Charsets.UTF_8))
            assertTrue("the device should accept the cast: ${welcome.reason}", welcome.ok)
            println("welcome: $welcome")

            val t0 = System.nanoTime()
            fun us() = (System.nanoTime() - t0) / 1000
            secure.writeMessage(Kind.CLOCK, stamp(us()))

            // A half-size solid-color JPEG frame, as -scale 2 asks for.
            val w = welcome.w / 2
            val h = welcome.h / 2
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, 0x3070C0)
            val jpegOut = ByteArrayOutputStream()
            ImageIO.write(img, "jpg", jpegOut)
            secure.writeMessage(Kind.VIDEO, stamp(us()), jpegOut.toByteArray())

            // A quarter second of a quiet 440 Hz tone, 48 kHz stereo S16LE.
            val samples = 48000 / 4
            val pcm = ByteArray(samples * 4)
            for (i in 0 until samples) {
                val v = (2000 * kotlin.math.sin(2 * Math.PI * 440 * i / 48000)).toInt().toShort()
                val lo = (v.toInt() and 0xFF).toByte()
                val hi = ((v.toInt() shr 8) and 0xFF).toByte()
                pcm[i * 4] = lo; pcm[i * 4 + 1] = hi
                pcm[i * 4 + 2] = lo; pcm[i * 4 + 3] = hi
            }
            secure.writeMessage(Kind.AUDIO, stamp(us()), pcm)

            Thread.sleep(500)
            secure.writeMessage(Kind.BYE)
            println("sent a frame and 250ms of audio to $addr; check the device log for cast decoded_fps and audio_late")
        }
    }
}

package dev.techo5.cast.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Proves this file's Noise handshake and message framing against the real Go implementation
 * (`wire/secure.go`, `wire/message.go`), not just against itself: a mismatch in PSK placement, the
 * prologue, or endianness would pass a Kotlin-only round trip and still fail against a real device.
 *
 * Uses `interop/main.go`, a small Go tool built once per test run, in both roles: as the responder
 * (this test dials in) and as the initiator (this test accepts). Skipped, rather than failed, if the
 * Go toolchain is not on PATH — the rest of the protocol module's tests still run.
 */
class WireTest {

    companion object {
        private lateinit var interopBinary: File
        private var goAvailable = true

        @JvmStatic
        @BeforeClass
        fun buildInterop() {
            val repoRoot = findRepoRoot()
            val go = findExecutable("go")
            if (go == null) {
                goAvailable = false
                return
            }
            interopBinary = File(repoRoot, "bin/interop")
            val build = ProcessBuilder(go, "build", "-o", interopBinary.absolutePath, "./interop")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            val output = build.inputStream.bufferedReader().readText()
            val ok = build.waitFor(60, TimeUnit.SECONDS) && build.exitValue() == 0
            check(ok) { "building interop/ failed:\n$output" }
        }

        /** Walks up from the working directory to find the repository root (the one holding `wire/`
         *  and `interop/`), so the test does not depend on which directory Gradle happens to run it
         *  from. */
        private fun findRepoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "wire").isDirectory && File(dir, "interop").isDirectory) return dir
                dir = dir.parentFile
            }
            error("could not find the techo5-cast repository root above ${System.getProperty("user.dir")}")
        }

        private fun findExecutable(name: String): String? {
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparator)) {
                val f = File(dir, name)
                if (f.canExecute()) return f.absolutePath
            }
            // Homebrew on Apple Silicon is often not on a Gradle daemon's inherited PATH.
            val brew = File("/opt/homebrew/bin/$name")
            if (brew.canExecute()) return brew.absolutePath
            return null
        }
    }

    private fun assumeGo() {
        org.junit.Assume.assumeTrue("the Go toolchain is not on PATH; skipping interop tests", goAvailable)
    }

    @Test
    fun `dial against the Go responder round-trips a hello and ends on bye`() {
        assumeGo()
        val key = "interop-test-key"
        val server = ProcessBuilder(interopBinary.absolutePath, "-role", "server", "-addr", "127.0.0.1:0", "-key", key)
            .redirectErrorStream(true)
            .start()
        try {
            val reader = BufferedReader(InputStreamReader(server.inputStream))
            val readyLine = reader.readLine() ?: fail("the Go server printed nothing: process alive=${server.isAlive}")
            val port = readyLine.removePrefix("ready ").trim().toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.tcpNoDelay = true
                val secure = dial(socket, key)

                val hello = Hello(name = "kotlin-test", video = true, audio = true, rate = 48000, channels = 2, scale = 2, title = "Big Buck Bunny")
                secure.writeMessage(Kind.HELLO, hello.toJson().toByteArray(Charsets.UTF_8))
                val echoed = secure.readMessage()
                assertEquals(Kind.HELLO, echoed.kind)
                assertEquals(hello, parseHello(String(echoed.payload, Charsets.UTF_8)))

                val payload = stamp(123_456_789L) + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
                secure.writeMessage(Kind.VIDEO, payload)
                val echoedVideo = secure.readMessage()
                assertEquals(Kind.VIDEO, echoedVideo.kind)
                assertArrayEquals(payload, echoedVideo.payload)

                secure.writeMessage(Kind.BYE)
                val echoedBye = secure.readMessage()
                assertEquals(Kind.BYE, echoedBye.kind)
            }
            assertTrue("the Go server should exit after echoing bye", server.waitFor(5, TimeUnit.SECONDS))
        } finally {
            server.destroy()
        }
    }

    @Test
    fun `accept from the Go initiator round-trips a welcome`() {
        assumeGo()
        val key = "interop-test-key-2"
        ServerSocket(0).use { serverSocket ->
            val client = ProcessBuilder(
                interopBinary.absolutePath, "-role", "client",
                "-addr", "127.0.0.1:${serverSocket.localPort}", "-key", key,
            ).redirectErrorStream(true).start()
            try {
                serverSocket.accept().use { socket ->
                    val secure = accept(socket, key)

                    val welcome = Welcome(ok = true, w = 960, h = 480, rate = 48000, channels = 2, latencyMs = 250)
                    secure.writeMessage(Kind.WELCOME, welcome.toJson().toByteArray(Charsets.UTF_8))
                    val echoed = secure.readMessage()
                    assertEquals(Kind.WELCOME, echoed.kind)
                    assertEquals(welcome, parseWelcome(String(echoed.payload, Charsets.UTF_8)))

                    secure.writeMessage(Kind.BYE)
                    val echoedBye = secure.readMessage()
                    assertEquals(Kind.BYE, echoedBye.kind)
                }
                assertTrue("the Go client should exit after echoing bye", client.waitFor(5, TimeUnit.SECONDS))
            } finally {
                client.destroy()
            }
        }
    }

    @Test
    fun `a wrong key is rejected by the Go responder`() {
        assumeGo()
        val server = ProcessBuilder(interopBinary.absolutePath, "-role", "server", "-addr", "127.0.0.1:0", "-key", "the-real-key")
            .redirectErrorStream(true)
            .start()
        try {
            val reader = BufferedReader(InputStreamReader(server.inputStream))
            val port = reader.readLine()!!.removePrefix("ready ").trim().toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                var threw = false
                try {
                    dial(socket, "the-wrong-key")
                } catch (e: Exception) {
                    threw = true
                }
                assertTrue("a wrong key should fail the handshake", threw)
            }
        } finally {
            server.destroy()
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}

package dev.techo5.cast.protocol

import com.southernstorm.noise.protocol.CipherState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import kotlin.math.min

/**
 * The connection between a sender and a TECHO5 device is encrypted with Noise, keyed by a pairing
 * key: the `NNpsk0` handshake, the same family the device's link to Home Assistant uses. Both ends
 * mix the key into the handshake, so a wrong key simply fails it; the key itself never crosses the
 * network, and everything after the handshake is encrypted and authenticated.
 *
 * This is the Kotlin twin of `wire/secure.go` in this repository; the two have to agree byte for
 * byte on the wire. `docs/protocol.md` is the specification, and [WireTest] proves this file against
 * the real Go code, not just against itself. [Handshake] is [dial]/[accept]'s actual implementation,
 * and its doc comment says why it is hand-written rather than a call into this Java library's own
 * `HandshakeState`.
 *
 * On the wire, the handshake's two messages and then every record are a 4-byte big-endian length and
 * that many bytes. A record holds at most [RECORD_MAX] bytes of the stream it carries, so a message
 * of any size is as many records as it takes.
 */
object Wire {
    const val PROLOGUE = "techo5-cast/1"

    /** Well inside Noise's 65535-byte message limit, with room for the tag. */
    const val RECORD_MAX = 60_000
    const val WIRE_MAX = RECORD_MAX + 64

    /** A handshake message is at most this many bytes: NNpsk0's are 48; nothing here needs more. */
    const val HANDSHAKE_MAX = 256

    /** The key as Noise wants it: 32 bytes, whatever the key's length. */
    fun psk(key: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("techo5-cast psk:" + key).toByteArray(Charsets.UTF_8))
}

/** Thrown when a peer's key does not match, or a record does not check out. */
class SecureException(message: String) : IOException(message)

private fun writeFrame(out: DataOutputStream, data: ByteArray, off: Int = 0, len: Int = data.size) {
    out.writeInt(len)
    out.write(data, off, len)
    out.flush()
}

private fun readFrame(input: DataInputStream, most: Int): ByteArray {
    val n = input.readInt()
    if (n <= 0 || n > most) throw SecureException("secure: a record of $n bytes")
    val buf = ByteArray(n)
    input.readFully(buf)
    return buf
}

/**
 * A connection after the handshake: what is written is encrypted, what is read decrypted and
 * checked. Mirrors `secureConn` in `wire/secure.go`, including that a message's own length prefix
 * (see [Message]) is separate from — and sits inside — the record framing here.
 */
class Secure internal constructor(
    private val socket: Socket,
    private val send: CipherState,
    private val recv: CipherState,
) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    // Plaintext left over from the last record decrypted, not yet handed to a caller.
    private var pending: ByteArray = ByteArray(0)
    private var pendingPos = 0

    /** Writes [data] as one or more encrypted, framed records. */
    @Synchronized
    fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = min(data.size - offset, Wire.RECORD_MAX)
            // The MAC is appended by encryptWithAd; give it the room it needs.
            val cipherBuf = ByteArray(n + send.getMACLength())
            val cipherLen = send.encryptWithAd(null, data, offset, cipherBuf, 0, n)
            writeFrame(output, cipherBuf, 0, cipherLen)
            offset += n
        }
        // An empty write is a no-op, as in wire/secure.go's Write: nothing is framed for zero bytes.
    }

    /** Reads exactly `buf.size` decrypted bytes, blocking until they arrive. Not thread-safe: only
     *  the one goroutine (thread) reading a connection may call this, as in the Go implementation. */
    fun readFully(buf: ByteArray) {
        var filled = 0
        while (filled < buf.size) {
            if (pendingPos >= pending.size) fillPending()
            val take = min(buf.size - filled, pending.size - pendingPos)
            System.arraycopy(pending, pendingPos, buf, filled, take)
            pendingPos += take
            filled += take
        }
    }

    private fun fillPending() {
        val cipherText = readFrame(input, Wire.WIRE_MAX)
        val plain = ByteArray(cipherText.size) // decrypted length is always <= ciphertext length
        val plainLen = try {
            recv.decryptWithAd(null, cipherText, 0, plain, 0, cipherText.size)
        } catch (e: Exception) {
            throw SecureException("secure: a record that does not check out")
        }
        pending = if (plainLen == plain.size) plain else plain.copyOf(plainLen)
        pendingPos = 0
    }

    override fun close() {
        socket.close()
    }
}

/** The sender's side of the handshake: a phone (or `castsend`) connecting to a device. Fails when
 *  the device's key is not this one. Mirrors `clientHandshake` / `dial` in `wire/secure.go`. */
fun dial(socket: Socket, key: String): Secure {
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    val prologue = Wire.PROLOGUE.toByteArray(Charsets.UTF_8)
    val psk = Wire.psk(key)

    val (send, recv) = NNpsk0.dial(
        read = { readFrame(input, Wire.HANDSHAKE_MAX) },
        write = { msg -> writeFrame(output, msg, 0, msg.size) },
        prologue = prologue,
        psk = psk,
    )
    return Secure(socket, send = send, recv = recv)
}

/** The device's side, here for the interop test against the Go reference server; a real device runs
 *  the Go code, not this. Mirrors `serverHandshake` / `accept` in `wire/secure.go`. */
fun accept(socket: Socket, key: String): Secure {
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    val prologue = Wire.PROLOGUE.toByteArray(Charsets.UTF_8)
    val psk = Wire.psk(key)

    val (send, recv) = NNpsk0.accept(
        read = { readFrame(input, Wire.HANDSHAKE_MAX) },
        write = { msg -> writeFrame(output, msg, 0, msg.size) },
        prologue = prologue,
        psk = psk,
    )
    return Secure(socket, send = send, recv = recv)
}

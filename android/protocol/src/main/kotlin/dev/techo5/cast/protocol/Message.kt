package dev.techo5.cast.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Message kinds and framing inside the encrypted stream a [Secure] connection carries. Mirrors
 * `wire/message.go`; `docs/protocol.md` is the specification.
 */
object Kind {
    const val HELLO: Byte = 1
    const val VIDEO: Byte = 2
    const val AUDIO: Byte = 3
    const val CLOCK: Byte = 4
    const val BYE: Byte = 5

    const val WELCOME: Byte = 0x10
    const val STOP: Byte = 0x11
    const val STATS: Byte = 0x12
}

/** The largest message either side accepts: a full-screen JPEG is well under 1 MB. */
const val MESSAGE_MAX = 4 shl 20

/**
 * Opens a cast. Audio is fixed at what the speaker plays, so a sender that cannot make it says so
 * here rather than the device playing it at the wrong speed. `scale` is 1 (frames are screen size)
 * or 2 (frames are half the screen's width and height, drawn doubled): see "Scale" in
 * `docs/protocol.md`.
 */
data class Hello(
    val name: String,
    val video: Boolean = false,
    val audio: Boolean = false,
    val rate: Int = 0,
    val channels: Int = 0,
    val scale: Int = 0,
    /** What is playing, for the Show to say on its screen for a moment. */
    val title: String = "",
)

/**
 * The Show's report on how the cast is going, about once a second. The counts are totals since the
 * cast began, so a sender takes differences.
 */
data class Stats(val shown: Int, val dropped: Int, val audioLate: Int, val audioDropped: Int)

/** Answers a [Hello]. `w`/`h` are the screen: frames larger than that are refused. */
data class Welcome(
    val ok: Boolean,
    val reason: String? = null,
    val w: Int = 0,
    val h: Int = 0,
    val rate: Int = 0,
    val channels: Int = 0,
    val latencyMs: Int = 0,
)

/** One message read off the wire: a kind and its payload. */
data class Message(val kind: Byte, val payload: ByteArray)

/** Sends one message: a length, the kind, then the parts, all as one record write. */
fun Secure.writeMessage(kind: Byte, vararg parts: ByteArray) {
    val total = 1 + parts.sumOf { it.size }
    if (total > MESSAGE_MAX) throw SecureException("wire: a message too large to send")
    val out = ByteArrayOutputStream(4 + total)
    val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(total).array()
    out.write(header)
    out.write(kind.toInt())
    for (part in parts) out.write(part)
    write(out.toByteArray())
}

/** Reads one message. */
fun Secure.readMessage(): Message {
    val header = ByteArray(4)
    readFully(header)
    val n = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
    if (n <= 0 || n > MESSAGE_MAX) throw SecureException("wire: a message of an impossible size")
    val body = ByteArray(n)
    readFully(body)
    return Message(kind = body[0], payload = body.copyOfRange(1, body.size))
}

/** The 8-byte microsecond stamp that leads a video, audio or clock payload. */
fun stamp(us: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(us).array()

/** Takes the stamp off the front of a payload; null if the payload is too short to hold one. */
fun splitStamp(payload: ByteArray): Pair<Long, ByteArray>? {
    if (payload.size < 8) return null
    val us = ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.BIG_ENDIAN).long
    return us to payload.copyOfRange(8, payload.size)
}

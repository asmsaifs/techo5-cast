package dev.techo5.cast.app

/** A failed connection to a Show, as a sentence for the person. */
fun describeFailure(e: Exception, device: String): String = when (e) {
    is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
        "Can't reach $device. Is Cast on, and is the phone on the same Wi-Fi?"
    // A wrong key fails the handshake: either our decrypt fails or the Show hangs up on us.
    is javax.crypto.BadPaddingException, is java.io.EOFException, is java.net.SocketException ->
        "The key doesn't match $device."
    else -> "Can't connect to $device: ${e.message ?: e.javaClass.simpleName}"
}

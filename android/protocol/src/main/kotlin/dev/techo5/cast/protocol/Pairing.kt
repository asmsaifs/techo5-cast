package dev.techo5.cast.protocol

import java.net.URI
import java.net.URLDecoder

/** What the Show's pairing QR code holds: where it is, its key and its name. */
data class Pairing(val host: String, val port: Int, val key: String, val name: String)

/**
 * Reads the text of a pairing code: `techo5cast://pair?host=...&port=...&key=...&name=...`, as the Show
 * draws it (`feature/cast` `PairingURL` on the device). Null for anything else, so a stray QR code in
 * the camera's view is ignored rather than filling the form with nonsense.
 */
fun parsePairing(text: String): Pairing? {
    val uri = try { URI(text.trim()) } catch (_: Exception) { return null }
    if (uri.scheme != "techo5cast" || uri.host != "pair") return null
    val query = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }.toMap()
    val host = query["host"]?.takeIf { it.isNotBlank() } ?: return null
    val key = query["key"]?.takeIf { it.isNotBlank() } ?: return null
    val port = (query["port"] ?: "8940").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return Pairing(host, port, key, query["name"].orEmpty())
}

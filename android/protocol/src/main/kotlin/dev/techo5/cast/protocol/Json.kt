package dev.techo5.cast.protocol

import org.json.JSONObject

/**
 * [Hello] and [Welcome] as the JSON line Go's `encoding/json` produces and reads (`wire/message.go`).
 * Field names have to match Go's tags exactly, including `Welcome.channels` for the field Go calls
 * `Channel` (singular) — the tag, not the Go name, is what's on the wire.
 *
 * This module is plain JVM, so it uses `org.json:json` rather than Android's built-in copy of the
 * same classes. A module that packages both into one app must exclude one of them
 * (`exclude(group = "org.json", module = "json")` on the Android side) or dexing fails on
 * duplicate classes — Android's own `org.json` is what actually runs there.
 */

fun Hello.toJson(): String {
    val o = JSONObject()
    o.put("name", name)
    if (video) o.put("video", true)
    if (audio) o.put("audio", true)
    if (rate != 0) o.put("rate", rate)
    if (channels != 0) o.put("channels", channels)
    if (scale != 0) o.put("scale", scale)
    return o.toString()
}

fun parseHello(json: String): Hello {
    val o = JSONObject(json)
    return Hello(
        name = o.optString("name", ""),
        video = o.optBoolean("video", false),
        audio = o.optBoolean("audio", false),
        rate = o.optInt("rate", 0),
        channels = o.optInt("channels", 0),
        scale = o.optInt("scale", 0),
    )
}

fun Welcome.toJson(): String {
    val o = JSONObject()
    o.put("ok", ok)
    if (!reason.isNullOrEmpty()) o.put("reason", reason)
    if (w != 0) o.put("w", w)
    if (h != 0) o.put("h", h)
    if (rate != 0) o.put("rate", rate)
    if (channels != 0) o.put("channels", channels)
    if (latencyMs != 0) o.put("latency_ms", latencyMs)
    return o.toString()
}

fun parseWelcome(json: String): Welcome {
    val o = JSONObject(json)
    return Welcome(
        ok = o.optBoolean("ok", false),
        reason = o.optString("reason", "").ifEmpty { null },
        w = o.optInt("w", 0),
        h = o.optInt("h", 0),
        rate = o.optInt("rate", 0),
        channels = o.optInt("channels", 0),
        latencyMs = o.optInt("latency_ms", 0),
    )
}

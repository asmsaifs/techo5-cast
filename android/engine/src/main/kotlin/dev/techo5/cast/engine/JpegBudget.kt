package dev.techo5.cast.engine

/** The lowest quality a frame is re-encoded at to fit; below this it is not worth showing. */
internal const val LOWEST_QUALITY = 30
private const val QUALITY_STEP = 15

/**
 * Encodes a frame so that it fits in [limit] bytes: at [quality] if that fits, otherwise again at lower
 * qualities, down to [LOWEST_QUALITY]. Null if it does not fit even then, and the frame is not sent: the
 * Show drops an oversize one without saying so. [encode] returns the JPEG at the quality it is given.
 */
internal fun encodeWithin(limit: Int, quality: Int, encode: (Int) -> ByteArray): ByteArray? {
    var q = quality
    while (true) {
        val jpeg = encode(q)
        if (jpeg.size <= limit) return jpeg
        if (q <= LOWEST_QUALITY) return null
        q = maxOf(q - QUALITY_STEP, LOWEST_QUALITY)
    }
}

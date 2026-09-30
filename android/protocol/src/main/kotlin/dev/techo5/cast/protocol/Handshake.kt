package dev.techo5.cast.protocol

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.Noise
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The `Noise_NNpsk0_25519_ChaChaPoly_SHA256` handshake, hand-written against the Noise spec and
 * checked line for line against `flynn/noise`'s implementation (the Go side's library, in
 * `wire/secure.go`), rather than driven through this Java library's own `HandshakeState`/`Pattern`
 * classes.
 *
 * Those classes turned out not to fit: they come from a build of `rweather/noise-java` that predates
 * the Noise spec's `psk0`-style pattern names, and use an older convention — a `NoisePSK` prefix,
 * with the pre-shared key mixed once at the very start of the handshake rather than as a token in the
 * message pattern. For a PSK mix that is a MixHash with no MixKey, that difference is invisible; the
 * actual Noise spec (and `flynn/noise`) also runs `MixKey`, which starts encrypting message 1's
 * (empty) payload with a real AEAD tag. This library's version does not, so message 1 comes out 16
 * bytes short of what the Go side sends and expects, and the handshake fails outright — confirmed
 * against a running Go server before writing this file.
 *
 * The pieces this file reuses from the library are only the well-defined primitives with no pattern
 * logic of their own: `Noise.createDH("25519")` for X25519, and `Noise.createCipher("ChaChaPoly")`
 * for the AEAD cipher, whose `encryptWithAd`/`decryptWithAd` already implement the
 * "no key set yet, so pass the plaintext through unchanged" rule the spec's `EncryptAndHash` needs
 * for messages before the cipher is keyed.
 */
internal object NNpsk0 {
    // "Noise_" + pattern name ("NN") + psk placement ("psk0") + "_" + DH + "_" + cipher + "_" + hash,
    // per the Noise spec's protocol-naming convention (spec §8) — this exact string seeds the
    // handshake hash, so it has to match flynn/noise's construction (state.go:356) byte for byte, even
    // though it is otherwise only ever compared against itself and never sent on the wire.
    private const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
    private const val DH_LEN = 32
    private const val HASH_LEN = 32

    /** `h`/`ck`/a single re-keyable cipher, and the DH state needed on this side of one handshake. */
    private class State(prologue: ByteArray, psk: ByteArray) {
        var h: ByteArray
        var ck: ByteArray
        val cipher: CipherState = Noise.createCipher("ChaChaPoly")
        var local: DHState = Noise.createDH("25519")

        init {
            val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
            h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else sha256(name)
            ck = h.copyOf()
            mixHash(prologue)
            mixKeyAndHash(psk)
        }

        fun mixHash(data: ByteArray) {
            h = sha256(h + data)
        }

        fun mixKey(dhOutput: ByteArray) {
            val (newCk, k) = hkdf2(ck, dhOutput)
            ck = newCk
            cipher.initializeKey(k, 0)
        }

        fun mixKeyAndHash(data: ByteArray) {
            val (newCk, tempH, k) = hkdf3(ck, data)
            ck = newCk
            mixHash(tempH)
            cipher.initializeKey(k, 0)
        }

        /** Encrypts (or, before the cipher is keyed, passes through) and mixes the result into `h`. */
        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val out = ByteArray(plaintext.size + 16)
            val n = cipher.encryptWithAd(h, plaintext, 0, out, 0, plaintext.size)
            val ciphertext = out.copyOf(n)
            mixHash(ciphertext)
            return ciphertext
        }

        /** Decrypts (or passes through) and mixes the *ciphertext* — not the plaintext — into `h`. */
        fun decryptAndHash(data: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            val n = try {
                cipher.decryptWithAd(h, data, 0, out, 0, data.size)
            } catch (e: Exception) {
                throw SecureException("secure: a message that does not check out")
            }
            mixHash(data)
            return out.copyOf(n)
        }

        /** The two directions' cipher states, once both messages have been processed. `ck`'s job is
         *  done at this point, so the input key material is empty, as `flynn/noise`'s `Split` uses. */
        fun split(): Pair<CipherState, CipherState> {
            val (k1, k2) = hkdf2(ck, ByteArray(0))
            val c1 = Noise.createCipher("ChaChaPoly").also { it.initializeKey(k1, 0) }
            val c2 = Noise.createCipher("ChaChaPoly").also { it.initializeKey(k2, 0) }
            return c1 to c2
        }
    }

    /** The initiator's two messages: `-> psk, e` then `<- e, ee`. Mirrors `wire.Dial` exactly, one
     *  Noise token at a time — see the class doc for why this isn't just a call into `HandshakeState`. */
    fun dial(read: () -> ByteArray, write: (ByteArray) -> Unit, prologue: ByteArray, psk: ByteArray): Pair<CipherState, CipherState> {
        val s = State(prologue, psk)

        // Message 1: "-> psk, e". The psk token (State's constructor) already ran; here just "e",
        // which — because this handshake uses a psk at all — is also mixed into the key, not only
        // the hash (flynn/noise state.go:413, the `willPsk` branch).
        s.local.generateKeyPair()
        val myPub = ByteArray(DH_LEN)
        s.local.getPublicKey(myPub, 0)
        s.mixHash(myPub)
        s.mixKey(myPub)
        write(myPub + s.encryptAndHash(ByteArray(0)))

        // Message 2: "<- e, ee".
        val msg2 = read()
        if (msg2.size < DH_LEN + 16) throw SecureException("secure: message 2 is too short")
        val theirPub = msg2.copyOfRange(0, DH_LEN)
        s.mixHash(theirPub)
        s.mixKey(theirPub)
        s.mixKey(dh(s.local, theirPub))
        s.decryptAndHash(msg2.copyOfRange(DH_LEN, msg2.size))

        val (c1, c2) = s.split() // c1: initiator -> responder, c2: responder -> initiator
        return c1 to c2 // (send, recv), for the initiator
    }

    /** The responder's side: reads `-> psk, e`, then writes `<- e, ee`. Mirrors `wire.Accept`. */
    fun accept(read: () -> ByteArray, write: (ByteArray) -> Unit, prologue: ByteArray, psk: ByteArray): Pair<CipherState, CipherState> {
        val s = State(prologue, psk)

        val msg1 = read()
        if (msg1.size < DH_LEN + 16) throw SecureException("secure: message 1 is too short")
        val theirPub = msg1.copyOfRange(0, DH_LEN)
        s.mixHash(theirPub)
        s.mixKey(theirPub)
        try {
            s.decryptAndHash(msg1.copyOfRange(DH_LEN, msg1.size))
        } catch (e: SecureException) {
            throw SecureException("secure: the sender's key is not this device's")
        }

        s.local.generateKeyPair()
        val myPub = ByteArray(DH_LEN)
        s.local.getPublicKey(myPub, 0)
        s.mixHash(myPub)
        s.mixKey(myPub)
        s.mixKey(dh(s.local, theirPub))
        write(myPub + s.encryptAndHash(ByteArray(0)))

        val (c1, c2) = s.split() // c1: initiator -> responder, c2: responder -> initiator
        return c2 to c1 // (send, recv), for the responder
    }

    /** `local`'s private key against a raw remote public key, via a throwaway [DHState] to hold it —
     *  the library's `DHState.calculate` wants one, not a bare byte array. */
    private fun dh(local: DHState, remotePublic: ByteArray): ByteArray {
        val remote = Noise.createDH("25519")
        remote.setPublicKey(remotePublic, 0)
        val out = ByteArray(local.getSharedKeyLength())
        local.calculate(out, 0, remote)
        return out
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC accepts an empty key (the Noise spec's `ck` is never actually empty here, but this
        // matches Go's crypto/hmac either way); Java's Mac requires a non-empty SecretKeySpec array,
        // which a chaining key always is (32 bytes).
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF per Noise spec §4.3 (`flynn/noise`'s `hkdf`, generalized to how many outputs are asked
     *  for): extract once, then expand outputs 0x01, 0x02, ... , each chained from the last. */
    private fun hkdfN(chainingKey: ByteArray, inputKeyMaterial: ByteArray, outputs: Int): List<ByteArray> {
        val tempKey = hmac(chainingKey, inputKeyMaterial)
        val out = mutableListOf<ByteArray>()
        var previous = ByteArray(0)
        for (i in 1..outputs) {
            previous = hmac(tempKey, previous + byteArrayOf(i.toByte()))
            out.add(previous)
        }
        return out
    }

    private fun hkdf2(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Pair<ByteArray, ByteArray> {
        val (a, b) = hkdfN(chainingKey, inputKeyMaterial, 2)
        return a to b
    }

    private fun hkdf3(chainingKey: ByteArray, inputKeyMaterial: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val (a, b, c) = hkdfN(chainingKey, inputKeyMaterial, 3)
        return Triple(a, b, c)
    }
}

package dev.techo5.cast.discovery

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A Show the person has added: where it is and the key that lets us in. */
data class Device(val name: String, val host: String, val port: Int, val key: String) {
    val id: String get() = "$host:$port"
}

/**
 * Saved devices. The list lives in app-private preferences; each Cast key is encrypted with an
 * AES key that never leaves the Android Keystore.
 */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun all(): List<Device> {
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val key = decrypt(o.optString("key")) ?: return@mapNotNull null
            Device(o.getString("name"), o.getString("host"), o.getInt("port"), key)
        }
    }

    fun find(id: String): Device? = all().firstOrNull { it.id == id }

    /** Adds the device, or replaces the one at the same address. */
    fun save(device: Device) {
        write(all().filterNot { it.id == device.id } + device)
    }

    fun remove(id: String) = write(all().filterNot { it.id == id })

    private fun write(list: List<Device>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(JSONObject().put("name", d.name).put("host", d.host).put("port", d.port).put("key", encrypt(d.key)))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    /** Null if it can't be read (the Keystore key was lost, say): the person types the key again. */
    private fun decrypt(stored: String): String? = try {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val ALIAS = "techo5cast-device-keys"
    }
}

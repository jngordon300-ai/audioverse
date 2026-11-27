package com.jaygordonai.audioverse.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores server credentials encrypted with an AES-256-GCM key held in the
 * Android Keystore (the key never leaves secure hardware where available).
 * Only ciphertext is written to SharedPreferences. App backup is disabled in the
 * manifest so ciphertext is not copied off the device either.
 *
 * Avoids androidx.security:security-crypto, which Google has deprecated.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("audioverse_secure", Context.MODE_PRIVATE)

    fun save(config: AuthConfig) {
        put(K_JF_HOST, config.jellyfinHost)
        put(K_JF_TOKEN, config.jellyfinToken)
        put(K_TUNNEL_HOST, config.tunnelHost)
        put(K_CF_ID, config.cfClientId)
        put(K_CF_SECRET, config.cfClientSecret)
        put(K_PRIVATE_HOST, config.privateHost)
    }

    fun load(): AuthConfig = AuthConfig(
        jellyfinHost = get(K_JF_HOST),
        jellyfinToken = get(K_JF_TOKEN),
        tunnelHost = get(K_TUNNEL_HOST),
        cfClientId = get(K_CF_ID),
        cfClientSecret = get(K_CF_SECRET),
        deviceId = deviceId(),
        privateHost = get(K_PRIVATE_HOST)
    )

    fun clear() {
        prefs.edit()
            .remove(K_JF_HOST).remove(K_JF_TOKEN).remove(K_TUNNEL_HOST)
            .remove(K_CF_ID).remove(K_CF_SECRET).remove(K_PRIVATE_HOST)
            .apply()
    }

    /** Random per-install ID required by Jellyfin; not tied to the person or hardware. */
    private fun deviceId(): String =
        prefs.getString(K_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(K_DEVICE_ID, it).apply()
        }

    private fun put(key: String, value: String?) {
        if (value.isNullOrBlank()) { prefs.edit().remove(key).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed, 0, IV_BYTES))
            String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key lost (e.g. device reset of lock screen) or data tampered: drop the value.
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "audioverse_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val K_JF_HOST = "jf_host"
        const val K_JF_TOKEN = "jf_token"
        const val K_TUNNEL_HOST = "tunnel_host"
        const val K_CF_ID = "cf_id"
        const val K_CF_SECRET = "cf_secret"
        const val K_DEVICE_ID = "device_id"
        const val K_PRIVATE_HOST = "private_host"
    }
}

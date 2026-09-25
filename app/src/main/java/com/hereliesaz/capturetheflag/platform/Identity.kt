package com.hereliesaz.capturetheflag.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.capturetheflag.net.Keys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The player's Nostr key and settings. The key is the player's identity on every node: made
 * once on this phone, never sent anywhere. Android's keystore can't hold a secp256k1 key, so
 * the secret is stored encrypted under an AES key that the keystore does hold and won't export.
 */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("ctf", Context.MODE_PRIVATE)

    val keys: Keys by lazy {
        prefs.getString(SECRET, null)?.let { runCatching { Keys(open(it)) }.getOrNull() }
            ?: Keys.generate().also { prefs.edit().putString(SECRET, seal(it.secret)).apply() }
    }

    /** The node to play on (`wss://…`), or null for the built-in single-phone test server. */
    var node: String?
        get() = prefs.getString(NODE, null)
        set(v) { prefs.edit().putString(NODE, v?.trim()?.takeIf { it.isNotEmpty() }).apply() }

    /** Name and selfie, so a node player isn't asked again every launch. */
    var profile: Pair<String, String>?
        get() = prefs.getString(NAME, null)?.let { n -> prefs.getString(SELFIE, null)?.let { n to it } }
        set(v) { prefs.edit().putString(NAME, v?.first).putString(SELFIE, v?.second).apply() }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun seal(secret: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(secret), Base64.NO_WRAP)
    }

    private fun open(stored: String): ByteArray {
        val b = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, b, 0, 12)) }
        return c.doFinal(b, 12, b.size - 12)
    }

    private companion object {
        const val ALIAS = "ctf-identity"
        const val SECRET = "secret"
        const val NODE = "node"
        const val NAME = "name"
        const val SELFIE = "selfie"
    }
}

package com.hereliesaz.capturetheflag.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Photos, selfies and stream video, stored by nodes under their SHA-256 (the Blossom
 * convention). Anyone can fetch by hash; uploading takes a signed kind-24242 authorization
 * naming the hash, so a node knows who put what there.
 *
 * Public media (selfies, stream segments) is stored as is. Private media (evidence photos: a
 * flag photo gives the flag away) is AES-GCM encrypted first, and the key travels only inside
 * the sealed action, as the fragment of its reference: `https://node/media/<sha>#k=<key>`.
 */
object Media {
    const val AUTH = 24242
    const val MAX_BYTES = 16 * 1024 * 1024

    fun sha(bytes: ByteArray) = Nostr.sha256(bytes).toHex()

    /** The node's media URL base, from its relay address: `wss://host/` → `https://host/media/`. */
    fun base(relay: String) = relay.trim().trimEnd('/').replaceFirst("wss://", "https://").replaceFirst("ws://", "http://") + "/media/"

    /** The `Authorization` header value for uploading [sha]. */
    fun auth(keys: Keys, sha: String, now: Long = System.currentTimeMillis() / 1000): String {
        val e = keys.sign(AUTH, "Upload $sha", listOf(listOf("t", "upload"), listOf("x", sha), listOf("expiration", (now + 300).toString())), now)
        return "Nostr " + Base64.getEncoder().encodeToString(Nostr.json.encodeToString(Event.serializer(), e).toByteArray())
    }

    /** Checks an `Authorization` header for an upload of [sha]. Returns the uploader's key, or null. */
    fun authorized(header: String?, sha: String, now: Long = System.currentTimeMillis() / 1000): String? {
        val raw = header?.removePrefix("Nostr ")?.takeIf { it != header } ?: return null
        val e = runCatching { Nostr.json.decodeFromString(Event.serializer(), String(Base64.getDecoder().decode(raw))) }.getOrNull() ?: return null
        val ok = e.valid() && e.kind == AUTH && e.tag("t") == "upload" && e.tag("x") == sha &&
            (e.tag("expiration")?.toLongOrNull() ?: 0) > now
        return e.pubkey.takeIf { ok }
    }

    /** Encrypts [plain] under a fresh key. Returns the blob to store and the key to share. */
    fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return nonce + c.doFinal(plain) to key
    }

    fun open(blob: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Splits a reference into its URL and, for private media, its key. */
    fun parse(ref: String): Pair<String, ByteArray?> {
        val (url, frag) = ref.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
        return url to frag?.removePrefix("k=")?.takeIf { it != frag }?.hex()
    }
}

/** Uploads to and downloads from one node's media store. */
class MediaClient(private val relay: String, private val http: HttpClient) {
    val base = Media.base(relay)

    companion object {
        /** A client for the node at [relay] (its `wss://` address), on its own HTTP engine. */
        fun open(relay: String) = MediaClient(relay, HttpClient(io.ktor.client.engine.cio.CIO))
    }

    /** Stores [bytes] as they are. Returns their URL, or null if the node refused. */
    suspend fun put(keys: Keys, bytes: ByteArray): String? {
        val sha = Media.sha(bytes)
        val r = runCatching { http.put(base + sha) { header("Authorization", Media.auth(keys, sha)); setBody(bytes) } }.getOrNull() ?: return null
        return (base + sha).takeIf { r.status.isSuccess() }
    }

    /** Stores [bytes] encrypted. Returns a reference only whoever it's shared with can open. */
    suspend fun putPrivate(keys: Keys, bytes: ByteArray): String? {
        val (blob, key) = Media.seal(bytes)
        return put(keys, blob)?.let { "$it#k=${key.toHex()}" }
    }

    /** Fetches and, for a private reference, decrypts. Checks the content against its hash. */
    suspend fun get(ref: String): ByteArray? {
        val (url, key) = Media.parse(ref)
        val body = runCatching { http.get(url) }.getOrNull()?.takeIf { it.status.isSuccess() }?.bodyAsBytes() ?: return null
        if (Media.sha(body) != url.substringAfterLast('/')) return null
        return if (key == null) body else runCatching { Media.open(body, key) }.getOrNull()
    }
}

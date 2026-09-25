package com.hereliesaz.capturetheflag.net

import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest
import java.security.SecureRandom

/** A NIP-01 event. Field names are the wire names. */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** First value of the first tag named [name]. */
    fun tag(name: String): String? = tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    /** The id matches the content and the signature matches the id and key. */
    fun valid(): Boolean = runCatching {
        id == Nostr.idOf(pubkey, created_at, kind, tags, content) &&
            Secp256k1.verifySchnorr(sig.hex(), id.hex(), pubkey.hex())
    }.getOrDefault(false)
}

/** A secp256k1 keypair. [pub] is the 32-byte x-only public key Nostr uses. */
class Keys(val secret: ByteArray) {
    val pub: String = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()

    fun sign(kind: Int, content: String, tags: List<List<String>> = emptyList(), at: Long = System.currentTimeMillis() / 1000): Event {
        val id = Nostr.idOf(pub, at, kind, tags, content)
        val aux = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sig = Secp256k1.signSchnorr(id.hex(), secret, aux).toHex()
        return Event(id, pub, at, kind, tags, content, sig)
    }

    companion object {
        fun generate(): Keys {
            val r = SecureRandom()
            while (true) {
                val k = ByteArray(32).also { r.nextBytes(it) }
                if (Secp256k1.secKeyVerify(k)) return Keys(k)
            }
        }
    }
}

object Nostr {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** NIP-01: sha256 of the compact JSON array `[0, pubkey, created_at, kind, tags, content]`. */
    fun idOf(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val canonical = buildJsonArray {
            add(JsonPrimitive(0)); add(JsonPrimitive(pubkey)); add(JsonPrimitive(createdAt)); add(JsonPrimitive(kind))
            add(JsonArray(tags.map { t -> JsonArray(t.map(::JsonPrimitive)) }))
            add(JsonPrimitive(content))
        }
        return sha256(canonical.toString().toByteArray()).toHex()
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}

/** A NIP-01 subscription filter. Tag filters are keyed by letter: `#e`, `#p`, `#g` (game), `#c` (city). */
data class Filter(
    val ids: Set<String>? = null,
    val authors: Set<String>? = null,
    val kinds: Set<Int>? = null,
    val tags: Map<String, Set<String>> = emptyMap(),
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
) {
    fun matches(e: Event): Boolean =
        (ids == null || e.id in ids) &&
            (authors == null || e.pubkey in authors) &&
            (kinds == null || e.kind in kinds) &&
            (since == null || e.created_at >= since) &&
            (until == null || e.created_at <= until) &&
            tags.all { (name, wanted) -> e.tags.any { it.size > 1 && it[0] == name && it[1] in wanted } }

    companion object {
        /** Parses a filter object from a REQ message. */
        fun parse(o: kotlinx.serialization.json.JsonObject): Filter {
            fun strings(k: String) = (o[k] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet()
            return Filter(
                ids = strings("ids"),
                authors = strings("authors"),
                kinds = (o["kinds"] as? JsonArray)?.map { (it as JsonPrimitive).content.toInt() }?.toSet(),
                tags = o.keys.filter { it.startsWith("#") && it.length == 2 }.associate { it.drop(1) to strings(it)!! },
                since = (o["since"] as? JsonPrimitive)?.content?.toLong(),
                until = (o["until"] as? JsonPrimitive)?.content?.toLong(),
                limit = (o["limit"] as? JsonPrimitive)?.content?.toInt(),
            )
        }
    }
}

fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

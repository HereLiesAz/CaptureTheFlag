package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.Media
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File

/**
 * The node's media store: files named by their SHA-256, so a file is its own proof of
 * integrity. The node stores ciphertext for private media and can't read it; it doesn't try.
 */
class MediaStore(private val dir: File, private val maxBytes: Int = Media.MAX_BYTES) {
    init { dir.mkdirs() }

    fun has(sha: String) = file(sha)?.exists() == true
    fun read(sha: String): ByteArray? = file(sha)?.takeIf { it.exists() }?.readBytes()

    /** Stores [bytes] if they hash to [sha]. False for a mismatch, or anything too big. */
    fun write(sha: String, bytes: ByteArray): Boolean {
        if (bytes.size > maxBytes || Media.sha(bytes) != sha) return false
        val f = file(sha) ?: return false
        if (!f.exists()) File(dir, "$sha.part").also { it.writeBytes(bytes) }.renameTo(f)
        return true
    }

    private fun file(sha: String) = sha.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { File(dir, it) }
}

/** `PUT /media/<sha>` with a signed upload authorization; `GET /media/<sha>` for anyone. */
fun Route.media(store: MediaStore) {
    put("/media/{sha}") {
        val sha = call.parameters["sha"]!!
        if (Media.authorized(call.request.header("Authorization"), sha) == null) return@put call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        val bytes = call.receiveChannel().readRemaining(Media.MAX_BYTES.toLong() + 1).readByteArray()
        if (!store.write(sha, bytes)) return@put call.respondText("Rejected: hash mismatch or too large", status = HttpStatusCode.BadRequest)
        call.respond(HttpStatusCode.OK)
    }
    get("/media/{sha}") {
        val bytes = store.read(call.parameters["sha"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }
}

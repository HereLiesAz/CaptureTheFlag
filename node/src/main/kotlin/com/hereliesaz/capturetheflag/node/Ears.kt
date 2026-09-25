package com.hereliesaz.capturetheflag.node

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File

/** What a referee heard in a stretch of stream audio. */
data class Heard(val said: Boolean, val transcript: String)

/** Listens to stream segments for the challenge words. Null when it can't listen at all. */
fun interface Ears {
    suspend fun heard(segments: List<ByteArray>, words: List<String>): Heard?
}

/**
 * Offline speech recognition with Vosk: the audio never leaves the node, and nobody's servers
 * hear it. Needs a Vosk model on disk and ffmpeg to pull the audio out of the video.
 *
 * It isn't asked to transcribe freely. It's told to listen for the challenge words and nothing
 * else ("[unk]" soaks up everything else), which makes it far more reliable than open
 * transcription, and it's all a referee needs to know.
 */
class VoskEars(modelDir: File, private val ffmpeg: String = "ffmpeg") : Ears {
    private val model by lazy { Model(modelDir.path) }

    override suspend fun heard(segments: List<ByteArray>, words: List<String>): Heard? = withContext(Dispatchers.IO) {
        val pcm = ByteArrayOutputStream()
        for (s in segments) pcm.write(audio(s) ?: return@withContext null)
        val grammar = JsonArray((words.map { it.lowercase() } + "[unk]").map(::JsonPrimitive)).toString()
        val text = Recognizer(model, SAMPLE_RATE, grammar).use { r ->
            val bytes = pcm.toByteArray()
            var at = 0
            while (at < bytes.size) {
                val n = minOf(CHUNK, bytes.size - at)
                r.acceptWaveForm(bytes.copyOfRange(at, at + n), n)
                at += n
            }
            // Partial results are finalised as it goes; the final result is only the tail, so gather both.
            (r.result.text() + " " + r.finalResult.text()).trim()
        }
        val heardWords = text.split(Regex("\\s+")).filter { it.isNotBlank() && it != "[unk]" }
        Heard(words.all { it.lowercase() in heardWords }, heardWords.joinToString(" "))
    }

    /** The segment's audio as 16 kHz mono 16-bit PCM, or null if ffmpeg can't read it. */
    private fun audio(segment: ByteArray): ByteArray? {
        val f = File.createTempFile("seg", ".mp4").apply { writeBytes(segment) }
        return try {
            val p = ProcessBuilder(ffmpeg, "-loglevel", "error", "-i", f.path, "-vn", "-ac", "1", "-ar", SAMPLE_RATE.toInt().toString(), "-f", "s16le", "-")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.readBytes()
            if (p.waitFor() == 0) out else null
        } catch (_: java.io.IOException) {
            null
        } finally {
            f.delete()
        }
    }

    private fun String.text() = runCatching {
        ((kotlinx.serialization.json.Json.parseToJsonElement(this) as JsonObject)["text"])?.jsonPrimitive?.content
    }.getOrNull().orEmpty()

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val CHUNK = 8_192
    }
}

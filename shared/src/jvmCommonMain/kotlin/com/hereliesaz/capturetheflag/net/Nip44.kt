package com.hereliesaz.capturetheflag.net

import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 version 2: authenticated encryption between two Nostr keys.
 *
 * conversation key = HKDF-extract(salt "nip44-v2", ECDH x-coordinate)
 * per message:       HKDF-expand(conversation key, nonce, 76) → ChaCha20 key, ChaCha20 nonce, HMAC key
 * payload:           base64(0x02 ‖ nonce ‖ ChaCha20(padded plaintext) ‖ HMAC-SHA256(nonce ‖ ciphertext))
 *
 * Padding hides message length to within a power-of-two-ish bucket. The HMAC is checked in
 * constant time before anything is decrypted.
 */
object Nip44 {
    private const val VERSION: Byte = 2
    private val SALT = "nip44-v2".toByteArray()

    /** The shared key between [secret] and [otherPub] (x-only hex). Symmetric: either side computes the same. */
    fun conversationKey(secret: ByteArray, otherPub: String): ByteArray {
        val point = Secp256k1.pubKeyTweakMul(byteArrayOf(0x02) + otherPub.hex(), secret)
        val sharedX = Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
        return hmac(SALT, sharedX)
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = random32()): String {
        val raw = plaintext.toByteArray(Charsets.UTF_8)
        require(raw.size in 1..65535) { "NIP-44 plaintext must be 1 to 65535 bytes" }
        val (key, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = chacha(key, chachaNonce, pad(raw))
        val mac = hmac(hmacKey, nonce + ciphertext)
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION) + nonce + ciphertext + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        val data = Base64.getDecoder().decode(payload)
        require(payload.length in 132..87472 && data.size in 99..65603 && data[0] == VERSION) { "Not a NIP-44 v2 payload" }
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (key, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        require(MessageDigest.isEqual(mac, hmac(hmacKey, nonce + ciphertext))) { "NIP-44 payload failed authentication" }
        return unpad(chacha(key, chachaNonce, ciphertext)).toString(Charsets.UTF_8)
    }

    /** Convenience: encrypt from [keys] to [recipient]. */
    fun seal(plaintext: String, keys: Keys, recipient: String) = encrypt(plaintext, conversationKey(keys.secret, recipient))

    /** Convenience: decrypt with [keys] something [sender] sealed to us. */
    fun open(payload: String, keys: Keys, sender: String) = decrypt(payload, conversationKey(keys.secret, sender))

    /** NIP-44 padding length: 32 minimum, then chunks that grow with the message. */
    fun paddedLength(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun pad(raw: ByteArray): ByteArray {
        val out = ByteArray(2 + paddedLength(raw.size))
        out[0] = (raw.size shr 8).toByte(); out[1] = raw.size.toByte()
        raw.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len in 1..65535 && padded.size == 2 + paddedLength(len)) { "NIP-44 bad padding" }
        return padded.copyOfRange(2, 2 + len)
    }

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(nonce.size == 32)
        val okm = hkdfExpand(conversationKey, nonce, 76)
        return Triple(okm.copyOfRange(0, 32), okm.copyOfRange(32, 44), okm.copyOfRange(44, 76))
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteBuffer.allocate(length)
        var t = ByteArray(0)
        var i: Byte = 1
        while (out.hasRemaining()) {
            t = hmac(prk, t + info + byteArrayOf(i++))
            out.put(t, 0, minOf(t.size, out.remaining()))
        }
        return out.array()
    }

    /**
     * ChaCha20 (RFC 8439), counter from 0, in plain Kotlin: every Android version and every JVM
     * gets the same cipher, whatever its crypto providers ship. Checked by the NIP-44 vectors.
     */
    private fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        fun le(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le(key, 4 * i)
        for (i in 0 until 3) state[13 + i] = le(nonce, 4 * i)
        val out = ByteArray(input.size)
        val x = IntArray(16)
        var counter = 0
        var off = 0
        while (off < input.size) {
            state[12] = counter++
            state.copyInto(x)
            fun qr(a: Int, b: Int, c: Int, d: Int) {
                x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
                x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
                x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
                x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
            }
            repeat(10) {
                qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
                qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
            }
            for (i in 0 until 64) {
                if (off + i >= input.size) break
                val word = x[i / 4] + state[i / 4]
                out[off + i] = (input[off + i].toInt() xor (word ushr (8 * (i % 4)))).toByte()
            }
            off += 64
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun random32() = ByteArray(32).also { SecureRandom().nextBytes(it) }
}

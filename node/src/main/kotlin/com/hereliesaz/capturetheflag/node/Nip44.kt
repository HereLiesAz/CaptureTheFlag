package com.hereliesaz.capturetheflag.node

import fr.acinq.secp256k1.Secp256k1
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.ChaCha20ParameterSpec
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

    private fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        }.doFinal(input)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun random32() = ByteArray(32).also { SecureRandom().nextBytes(it) }
}

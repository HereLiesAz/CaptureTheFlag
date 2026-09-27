package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.Evidence
import com.hereliesaz.capturetheflag.net.hex
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Checks, offline, that evidence came off a genuine phone. The app signs each evidence photo's
 * details with a key made in the phone's secure hardware; Android vouches for that key with a
 * certificate chain up to one of Google's attestation roots. The chain's leaf says where the key
 * lives, whether the bootloader is locked and the boot verified, and carries the challenge the
 * app set when it made the key: the player's own Nostr key, so a chain can't be lent to someone else.
 *
 * Rooted phones, unlocked bootloaders, emulators and software-only keystores all fail. That's
 * the point, and it will annoy some players. Google's revocation list is online-only, so it isn't
 * checked; a revoked device model passes until the roots themselves change.
 */
class KeyAttestation(private val roots: Set<List<Byte>> = googleRoots()) {

    /** Null when [e] is attested by [player]'s own hardware key; otherwise why not. */
    fun check(e: Evidence, player: String): String? {
        val att = e.att ?: return "Evidence isn't attested"
        val chain = runCatching {
            val cf = CertificateFactory.getInstance("X.509")
            att.chain.map { cf.generateCertificate(Base64.getDecoder().decode(it).inputStream()) as X509Certificate }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return "Attestation chain unreadable"
        for (i in 0 until chain.lastIndex) if (runCatching { chain[i].verify(chain[i + 1].publicKey) }.isFailure) return "Attestation chain broken"
        val root = chain.last()
        if (root.publicKey.encoded.toList() !in roots || runCatching { root.verify(root.publicKey) }.isFailure) return "Attestation not rooted at Google"

        val leaf = chain.first()
        val desc = leaf.getExtensionValue(OID)?.let { runCatching { KeyDescription.parse(Der.read(it).content) }.getOrNull() }
            ?: return "No key description"
        if (desc.securityLevel !in setOf(TEE, STRONGBOX)) return "Key isn't in secure hardware"
        if (!desc.challenge.contentEquals(player.hex())) return "Key belongs to someone else"
        val trust = desc.rootOfTrust ?: return "No root of trust"
        if (!trust.locked || trust.bootState != VERIFIED) return "Phone is unlocked or rooted"

        val alg = if (leaf.publicKey.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val ok = runCatching {
            Signature.getInstance(alg).run { initVerify(leaf.publicKey); update(e.signedBytes()); verify(Base64.getDecoder().decode(att.sig)) }
        }.getOrDefault(false)
        return if (ok) null else "Evidence signature doesn't match"
    }

    /** The parts of Android's KeyDescription (the attestation extension) that matter here. */
    internal class KeyDescription(val securityLevel: Int, val challenge: ByteArray, val rootOfTrust: RootOfTrust?) {
        class RootOfTrust(val locked: Boolean, val bootState: Int)
        companion object {
            fun parse(der: ByteArray): KeyDescription {
                val f = Der.read(der).children()
                val hardware = f[7].children()
                val rot = hardware.firstOrNull { it.cls == 2 && it.number == 704 }?.let { Der.read(it.content).children() }
                return KeyDescription(f[1].int(), f[4].content, rot?.let { RootOfTrust(it[1].content.single() != 0.toByte(), it[2].int()) })
            }
        }
    }

    /** Just enough DER to read a KeyDescription: tag class and number, and content. */
    internal class Der(val cls: Int, val number: Int, val content: ByteArray) {
        fun children(): List<Der> {
            val out = mutableListOf<Der>()
            var i = 0
            while (i < content.size) { val (d, next) = at(content, i); out += d; i = next }
            return out
        }
        fun int(): Int = content.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

        companion object {
            fun read(b: ByteArray) = at(b, 0).first
            private fun at(b: ByteArray, start: Int): Pair<Der, Int> {
                var i = start
                val first = b[i++].toInt() and 0xff
                var number = first and 0x1f
                if (number == 0x1f) {
                    number = 0
                    do { val x = b[i++].toInt() and 0xff; number = (number shl 7) or (x and 0x7f) } while (x and 0x80 != 0)
                }
                var len = b[i++].toInt() and 0xff
                if (len and 0x80 != 0) {
                    val n = len and 0x7f
                    len = 0
                    repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xff) }
                }
                return Der(first shr 6, number, b.copyOfRange(i, i + len)) to i + len
            }
        }
    }

    companion object {
        const val OID = "1.3.6.1.4.1.11129.2.1.17"
        private const val TEE = 1
        private const val STRONGBOX = 2
        private const val VERIFIED = 0

        /** Google's hardware attestation roots (android.googleapis.com/attestation/root), by public key. */
        fun googleRoots(): Set<List<Byte>> {
            val cf = CertificateFactory.getInstance("X.509")
            val pem = KeyAttestation::class.java.getResourceAsStream("/attestation-roots.pem")!!
            return cf.generateCertificates(pem).map { it.publicKey.encoded.toList() }.toSet()
        }
    }
}

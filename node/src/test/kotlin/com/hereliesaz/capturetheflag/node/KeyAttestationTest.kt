package com.hereliesaz.capturetheflag.node

import com.hereliesaz.capturetheflag.net.Evidence
import com.hereliesaz.capturetheflag.net.Keys
import com.hereliesaz.capturetheflag.net.hex
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stand-in chains shaped like Android's: a root we trust in place of Google's, and a leaf carrying a KeyDescription. */
class KeyAttestationTest {
    private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val root = ec()
    private val rootCert = cert(root, root, "CN=root", "CN=root", null)
    private val player = Keys.generate()
    private val verifier = KeyAttestation(setOf(root.public.encoded.toList()))
    private val evidence = Evidence(image = "https://node/media/abc", lat = 29.95, lng = -90.07, takenAt = 1_000)

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val len = if (body.size < 128) byteArrayOf(body.size.toByte()) else byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        val t = if (tag > 0xff) byteArrayOf((tag shr 16).toByte(), (tag shr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
        return t + len + body
    }
    private fun seq(vararg xs: ByteArray) = tlv(0x30, xs.fold(ByteArray(0)) { a, b -> a + b })

    /** KeyDescription: version, security level, keymint version and level, challenge, unique id, software list, hardware list. */
    private fun description(challenge: ByteArray, level: Int = 1, locked: Boolean = true, boot: Int = 0): ByteArray {
        val rootOfTrust = seq(tlv(0x04, ByteArray(32)), tlv(0x01, byteArrayOf(if (locked) -1 else 0)), tlv(0x0a, byteArrayOf(boot.toByte())), tlv(0x04, ByteArray(32)))
        return seq(
            tlv(0x02, byteArrayOf(100)), tlv(0x0a, byteArrayOf(level.toByte())), tlv(0x02, byteArrayOf(100)), tlv(0x0a, byteArrayOf(level.toByte())),
            tlv(0x04, challenge), tlv(0x04, ByteArray(0)), seq(), seq(tlv(0xbf8540, rootOfTrust)),
        )
    }

    private fun cert(subject: KeyPair, issuer: KeyPair, name: String, issuerName: String, ext: ByteArray?): X509Certificate {
        val b = JcaX509v3CertificateBuilder(X500Name(issuerName), BigInteger.ONE, Date(0), Date(4_000_000_000_000), X500Name(name), subject.public)
        ext?.let { b.addExtension(ASN1ObjectIdentifier(KeyAttestation.OID), false, it) }
        return JcaX509CertificateConverter().getCertificate(b.build(JcaContentSignerBuilder("SHA256withECDSA").build(issuer.private)))
    }

    private fun attested(e: Evidence, desc: ByteArray = description(player.pub.hex()), trustedRoot: KeyPair = root, signed: ByteArray = e.signedBytes()): Evidence {
        val leaf = ec()
        val top = if (trustedRoot === root) rootCert else cert(trustedRoot, trustedRoot, "CN=root", "CN=root", null)
        val chain = listOf(cert(leaf, trustedRoot, "CN=leaf", "CN=root", desc), top)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(leaf.private); update(signed); sign() }
        val b64 = Base64.getEncoder()
        return e.copy(att = Evidence.Attestation(chain.map { b64.encodeToString(it.encoded) }, b64.encodeToString(sig)))
    }

    @Test fun aGenuinePhonesEvidencePasses() = assertNull(verifier.check(attested(evidence), player.pub))

    @Test fun everythingElseIsRefused() {
        assertEquals("Evidence isn't attested", verifier.check(evidence, player.pub))
        assertEquals("Key belongs to someone else", verifier.check(attested(evidence), Keys.generate().pub))
        assertEquals("Key belongs to someone else", verifier.check(attested(evidence, description(Keys.generate().pub.hex())), player.pub))
        assertEquals("Key isn't in secure hardware", verifier.check(attested(evidence, description(player.pub.hex(), level = 0)), player.pub))
        assertEquals("Phone is unlocked or rooted", verifier.check(attested(evidence, description(player.pub.hex(), locked = false)), player.pub))
        assertEquals("Phone is unlocked or rooted", verifier.check(attested(evidence, description(player.pub.hex(), boot = 2)), player.pub))
        assertEquals("Attestation not rooted at Google", verifier.check(attested(evidence, trustedRoot = ec()), player.pub))
        val a = attested(evidence)
        assertEquals("Evidence signature doesn't match", verifier.check(a.copy(lat = 30.0), player.pub), "moved after signing")
    }

    @Test fun googlesRootsLoad() = assertEquals(2, KeyAttestation.googleRoots().size)
}

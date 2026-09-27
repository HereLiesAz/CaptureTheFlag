package com.hereliesaz.capturetheflag.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hereliesaz.capturetheflag.net.Evidence
import com.hereliesaz.capturetheflag.net.Keys
import com.hereliesaz.capturetheflag.net.hex
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The phone's evidence key: made in secure hardware, never leaves it, and attested by Android
 * with a certificate chain up to Google. The attestation challenge is the player's Nostr key, so
 * the chain names whose phone this is. Referees check it offline (KeyAttestation, on the node).
 *
 * Null from [sign] where the phone can't attest (emulators, some old devices): the evidence goes
 * unattested, and referees that require attestation refuse it.
 */
class HardwareKey(private val player: Keys) {
    private val alias = "ctf-evidence-${player.pub.take(16)}"

    fun sign(bytes: ByteArray): Evidence.Attestation? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) make()
        val key = ks.getKey(alias, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }
        val chain = ks.getCertificateChain(alias).map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        Evidence.Attestation(chain, Base64.encodeToString(sig, Base64.NO_WRAP))
    }.getOrNull()

    private fun make() {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(player.pub.hex())
                    .build(),
            )
        }.generateKeyPair()
    }
}

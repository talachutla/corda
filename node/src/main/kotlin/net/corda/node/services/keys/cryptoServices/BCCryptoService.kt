package net.corda.node.services.keys.cryptoServices

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.newSecureRandom
import net.corda.cryptoservice.CryptoService
import net.corda.node.services.config.NodeConfiguration
import net.corda.nodeapi.internal.crypto.ContentSignerBuilder
import net.corda.nodeapi.internal.crypto.X509Utilities
import org.bouncycastle.operator.ContentSigner
import java.security.PublicKey

/**
 * Basic implementation of a [CryptoService] that uses BouncyCastle for cryptographic operations
 * and a Java KeyStore to store private keys.
 */
internal class BCCryptoService(private val nodeConf: NodeConfiguration) : CryptoService {

    // TODO check if keyStore exists.
    // TODO make it work with nodeConf.cryptoServiceConf (if it exists). I.e., read the signingCertificateStore
    //      keyStore file name from there.
    var keyStore = nodeConf.signingCertificateStore.get(true).value

    override fun generateKeyPair(alias: String, schemeNumberID: Int): PublicKey {
        val keyPair = Crypto.generateKeyPair(Crypto.findSignatureScheme(schemeNumberID))
        // Store a self-signed certificate, as Keystore requires to store certificates instead of public keys.
        // We could probably add a null cert, but we store a self-signed cert that will be used to retrieve the public key.
        val cert = X509Utilities.createSelfSignedCACertificate(nodeConf.myLegalName.x500Principal, keyPair)
        keyStore.setPrivateKey(alias, keyPair.private, listOf(cert))
        return keyPair.public
    }

    override fun containsKey(alias: String): Boolean {
        return keyStore.contains(alias)
    }

    override fun getPublicKey(alias: String): PublicKey {
        return keyStore.getPublicKey(alias)
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        return Crypto.doSign(keyStore.getPrivateKey(alias), data)
    }

    override fun signer(alias: String): ContentSigner {
        val privateKey = keyStore.getPrivateKey(alias)
        val signatureScheme = Crypto.findSignatureScheme(privateKey)
        return ContentSignerBuilder.build(signatureScheme, privateKey, Crypto.findProvider(signatureScheme.providerName), newSecureRandom())
    }

    override fun enlist(): List<String> {
        return keyStore.aliases().asSequence().toList()
    }

    fun resyncKeystore() {
        keyStore = nodeConf.signingCertificateStore.get(true).value
    }
}

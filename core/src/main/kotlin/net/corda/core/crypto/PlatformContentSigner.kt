package net.corda.core.crypto

import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.PrivateKey

class PlatformContentSigner(private val privateKey: PrivateKey) : ContentSigner {

    private val outputStream = ByteArrayOutputStream()
    private val signatureOID: AlgorithmIdentifier by lazy { Crypto.findSignatureScheme(privateKey).signatureOID }

    override fun getAlgorithmIdentifier(): AlgorithmIdentifier {
        return signatureOID
    }

    override fun getOutputStream(): OutputStream {
        return outputStream
    }

    override fun getSignature(): ByteArray {
        return Crypto.doSign(privateKey, outputStream.toByteArray())
    }
}
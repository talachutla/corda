package net.corda.nodeapi.internal.crypto

import net.corda.core.crypto.Crypto
import net.corda.nodeapi.internal.crypto.X509Utilities.createSelfSignedCACertificate
import java.math.BigInteger
import javax.security.auth.x500.X500Principal

object DummyKeysAndCerts {
    val DUMMY_ECDSAR1_KEYPAIR by lazy { Crypto.deriveKeyPairFromEntropy(Crypto.ECDSA_SECP256R1_SHA256, BigInteger.valueOf(0)) }
    val DUMMY_ECDSAR1_CERT by lazy { createSelfSignedCACertificate(X500Principal("CN=DUMMY"), DUMMY_ECDSAR1_KEYPAIR) }
}

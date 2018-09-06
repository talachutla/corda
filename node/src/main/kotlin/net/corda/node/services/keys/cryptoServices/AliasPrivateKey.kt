package net.corda.node.services.keys.cryptoServices

import java.security.PrivateKey

/** [PrivateKey] wrapper to just store the alias of a private key. */
class AliasPrivateKey(val alias: String): PrivateKey {
    override fun getAlgorithm() = "ALIAS"
    override fun getEncoded() = ByteArray(0)
    override fun getFormat() = "ALIAS-String"
}

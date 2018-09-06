package net.corda.cryptoservice

import org.bouncycastle.operator.ContentSigner
import java.security.PublicKey

interface CryptoService {

    /** schemeNumberID is Corda specific. */
    fun generateKeyPair(alias: String, schemeNumberID: Int): PublicKey
    fun containsKey(alias: String): Boolean
    fun getPublicKey(alias: String): PublicKey?
    fun sign(alias: String, data: ByteArray): ByteArray
    fun signer(alias: String): ContentSigner
    fun enlist(): List<String>
}
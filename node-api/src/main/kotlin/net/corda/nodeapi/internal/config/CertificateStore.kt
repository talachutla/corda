package net.corda.nodeapi.internal.config

import net.corda.core.internal.outputStream
import net.corda.nodeapi.internal.crypto.DummyKeysAndCerts
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.addOrReplaceCertificate
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.OpenOption
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate

interface CertificateStore : Iterable<Pair<String, X509Certificate>> {

    companion object {

        fun of(store: X509KeyStore, password: String): CertificateStore = DelegatingCertificateStore(store, password)

        fun fromFile(storePath: Path, password: String, createNew: Boolean): CertificateStore = DelegatingCertificateStore(X509KeyStore.fromFile(storePath, password, createNew), password)

        fun fromInputStream(stream: InputStream, password: String): CertificateStore = DelegatingCertificateStore(X509KeyStore.fromInputStream(stream, password), password)

        fun fromResource(storeResourceName: String, password: String, classLoader: ClassLoader = Thread.currentThread().contextClassLoader): CertificateStore = fromInputStream(classLoader.getResourceAsStream(storeResourceName), password)
    }

    val value: X509KeyStore
    val password: String

    fun writeTo(stream: OutputStream) = value.internal.store(stream, password.toCharArray())

    fun writeTo(path: Path, vararg options: OpenOption) = path.outputStream(*options)

    fun update(action: X509KeyStore.() -> Unit) {
        val result = action.invoke(value)
        value.save()
        return result
    }

    fun <RESULT> query(action: X509KeyStore.() -> RESULT): RESULT {
        return action.invoke(value)
    }

    operator fun set(alias: String, certificate: X509Certificate) {
        update {
            internal.addOrReplaceCertificate(X509Utilities.CORDA_ROOT_CA, certificate)
        }
    }

    override fun iterator(): Iterator<Pair<String, X509Certificate>> {

        return query {
            aliases()
        }.asSequence().map { alias -> alias to get(alias) }.iterator()
    }

    fun forEach(action: (alias: String, certificate: X509Certificate) -> Unit) {

        forEach { (alias, certificate) -> action.invoke(alias, certificate) }
    }

    /**
     * @throws IllegalArgumentException if no certificate for the alias is found, or if the certificate is not an [X509Certificate].
     */
    operator fun get(alias: String): X509Certificate {
        return query {
            getCertificate(alias)
        }
    }

    operator fun contains(alias: String): Boolean = value.contains(alias)

    fun copyTo(certificateStore: CertificateStore) {

        certificateStore.update {
            this@CertificateStore.forEach(::setCertificate)
        }
    }

    fun setCertPathOnly(alias: String, certificates: List<X509Certificate>) {
        // In case CryptoService and CertificateStore share the same KeyStore, extract and store the key again.
        val privateKey = if (this.contains(alias)) {
            this.value.getPrivateKey(alias)
        } else {
            DummyKeysAndCerts.DUMMY_ECDSAR1_KEYPAIR.private
        }
        this.value.setPrivateKey(alias, privateKey, certificates)
    }
}

private class DelegatingCertificateStore(override val value: X509KeyStore, override val password: String) : CertificateStore

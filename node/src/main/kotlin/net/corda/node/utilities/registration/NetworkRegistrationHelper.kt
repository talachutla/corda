package net.corda.node.utilities.registration

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.NullKeys
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.*
import net.corda.core.utilities.contextLogger
import net.corda.node.NodeRegistrationOption
import net.corda.node.services.config.NodeConfiguration
import net.corda.nodeapi.internal.config.CertificateStore
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.DummyKeysAndCerts
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_CLIENT_TLS
import net.corda.nodeapi.internal.crypto.X509Utilities.CORDA_ROOT_CA
import net.corda.nodeapi.internal.crypto.X509Utilities.DEFAULT_VALIDITY_WINDOW
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.util.io.pem.PemObject
import java.io.IOException
import java.io.StringWriter
import java.net.ConnectException
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.time.Duration
import javax.naming.ServiceUnavailableException
import javax.security.auth.x500.X500Principal

/**
 * Helper for managing the node registration process, which checks for any existing certificates and requests them if
 * needed.
 */
// TODO: Use content signer instead of keypairs.
open class NetworkRegistrationHelper(
        config: NodeConfiguration,
        private val certService: NetworkRegistrationService,
        private val networkRootTrustStorePath: Path,
        networkRootTrustStorePassword: String,
        private val keyAlias: String,
        private val certRole: CertRole,
        private val nextIdleDuration: (Duration?) -> Duration? = FixedPeriodLimitedRetrialStrategy(10, Duration.ofMinutes(1))
) {

    companion object {
        const val SELF_SIGNED_PRIVATE_KEY = "SelfSignedPrivateKey"
        val logger = contextLogger()
    }
    private val certificatesDirectory: Path = config.certificatesDirectory
    private val myLegalName: CordaX500Name = config.myLegalName
    private val emailAddress: String = config.emailAddress
    private val cryptoService = config.makeCryptoService()
    private val certificateStore = config.signingCertificateStore.get(true)
    private val requestIdStore = certificatesDirectory / "certificate-request-id.txt"
    protected val rootTrustStore: X509KeyStore
    protected val rootCert: X509Certificate

    init {
        require(networkRootTrustStorePath.exists()) {
            "$networkRootTrustStorePath does not exist. This file must contain the root CA cert of your compatibility zone. " +
                    "Please contact your CZ operator."
        }
        rootTrustStore = X509KeyStore.fromFile(networkRootTrustStorePath, networkRootTrustStorePassword)
        rootCert = rootTrustStore.getCertificate(CORDA_ROOT_CA)
    }

    /**
     * Ensure the initial keys and certificates for a node are set up.
     *
     * This checks the "config.certificatesDirectory" field for certificates required to connect to a Corda network.
     * If the certificates are not found, a PKCS #10 certification request will be submitted to the
     * Corda network permissioning server via [NetworkRegistrationService]. This process will enter a polling loop until
     * the request has been approved, and then the certificate chain will be downloaded and stored in [certificateStore].
     *
     * @throws CertificateRequestException if the certificate retrieved by doorman is invalid.
     */
    fun generateKeysAndRegister() {
        certificatesDirectory.createDirectories()
        // In case CryptoService and CertificateStore share the same KeyStore (for backwards compatibility) we use
        // the SELF_SIGNED_PRIVATE_KEY as progress indicator.
        if (certificateStore.contains(keyAlias) && !certificateStore.contains(SELF_SIGNED_PRIVATE_KEY)) {
            println("Certificate already exists, Corda node will now terminate...")
            return
        }

        val tlsCrlIssuerCert = getTlsCrlIssuerCert()

        // We use this as progress indicator so we just store a dummy key and cert.
        // When registration succeeds, this entry should be deleted.
        certificateStore.value.setPrivateKey(SELF_SIGNED_PRIVATE_KEY, DummyKeysAndCerts.DUMMY_ECDSAR1_KEYPAIR.private, listOf(DummyKeysAndCerts.DUMMY_ECDSAR1_CERT))
        val publicKey = loadOrGenerateKeyPair()

        val requestId = submitOrResumeCertificateSigningRequest(publicKey, cryptoService.signer(keyAlias))

        val certificates = pollServerForCertificates(requestId)
        validateCertificates(publicKey, certificates)

        certificateStore.setCertPathOnly(keyAlias, certificates)
        certificateStore.value.internal.deleteEntry(SELF_SIGNED_PRIVATE_KEY)
        certificateStore.value.save()
        println("Private key '$keyAlias' and its certificate-chain stored successfully.")

        onSuccess(publicKey, cryptoService.signer(keyAlias), certificates, tlsCrlIssuerCert?.subjectX500Principal?.toX500Name())
        // All done, clean up temp files.
        requestIdStore.deleteIfExists()
    }

    private fun storeCertificates(certificates: List<X509Certificate>) {
        // In case CryptoService and CertificateStore share the same KeyStore, extract and store the key again.
        val privateKey = if (certificateStore.contains(keyAlias)) {
            certificateStore.value.getPrivateKey(keyAlias)
        } else {
            DummyKeysAndCerts.DUMMY_ECDSAR1_KEYPAIR.private
        }
        certificateStore.value.setPrivateKey(keyAlias, privateKey, certificates)
    }

    private fun loadOrGenerateKeyPair(): PublicKey {
        return if (cryptoService.containsKey(keyAlias)) {
            cryptoService.getPublicKey(keyAlias)!!
        } else {
            cryptoService.generateKeyPair(keyAlias, X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME.schemeNumberID)
        }
    }

    private fun getTlsCrlIssuerCert(): X509Certificate? {
        val tlsCrlIssuerCert = validateAndGetTlsCrlIssuerCert()
        if (tlsCrlIssuerCert == null && isTlsCrlIssuerCertRequired()) {
            System.err.println("""tlsCrlIssuerCert config does not match the root certificate issuer and nor is there any other certificate in the trust store with a matching issuer.
                    | Please make sure the config is correct or that the correct certificate for the CRL issuer is added to the node's trust store.
                    | The node will now terminate.""".trimMargin())
            throw IllegalArgumentException("TLS CRL issuer certificate not found in the trust store.")
        }
        return tlsCrlIssuerCert
    }

    private fun validateCertificates(registeringPublicKey: PublicKey, certificates: List<X509Certificate>) {
        val nodeCACertificate = certificates.first()

        val nodeCaSubject = try {
            CordaX500Name.build(nodeCACertificate.subjectX500Principal)
        } catch (e: IllegalArgumentException) {
            throw CertificateRequestException("Received node CA cert has invalid subject name: ${e.message}")
        }
        if (nodeCaSubject != myLegalName) {
            throw CertificateRequestException("Subject of received node CA cert doesn't match with node legal name: $nodeCaSubject")
        }

        val nodeCaCertRole = try {
            CertRole.extract(nodeCACertificate)
        } catch (e: IllegalArgumentException) {
            throw CertificateRequestException("Unable to extract cert role from received node CA cert: ${e.message}")
        }

        if (certRole != nodeCaCertRole) {
            throw CertificateRequestException("Received certificate contains invalid cert role, expected '$certRole', got '$nodeCaCertRole'.")
        }

        // Validate returned certificate is for the correct public key.
        if (Crypto.toSupportedPublicKey(certificates.first().publicKey) != Crypto.toSupportedPublicKey(registeringPublicKey)) {
            throw CertificateRequestException("Received certificate contains incorrect public key, expected '$registeringPublicKey', got '${certificates.first().publicKey}'.")
        }

        // Validate certificate chain returned from the doorman with the root cert obtained via out-of-band process, to prevent MITM attack on doorman server.
        X509Utilities.validateCertificateChain(rootCert, certificates)
        println("Certificate signing request approved, storing private key with the certificate chain.")
    }

    private fun CertificateStore.loadOrCreateKeyPair(alias: String, privateKeyPassword: String = password): KeyPair {
        // Create or load self signed keypair from the key store.
        // We use the self sign certificate to store the key temporarily in the keystore while waiting for the request approval.
        if (alias !in this) {
            // NODE_CA should be TLS compatible due to the cert hierarchy structure.
            val keyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val selfSignCert = X509Utilities.createSelfSignedCACertificate(myLegalName.x500Principal, keyPair)
            // Save to the key store.
            with(value) {
                setPrivateKey(alias, keyPair.private, listOf(selfSignCert), keyPassword = privateKeyPassword)
            }
        }
        return query { getCertificateAndKeyPair(alias, privateKeyPassword) }.keyPair
    }

    /**
     * Poll Certificate Signing Server for approved certificate,
     * enter a slow polling loop if server return null.
     * @param requestId Certificate signing request ID.
     * @return List of certificate chain.
     */
    private fun pollServerForCertificates(requestId: String): List<X509Certificate> {
        try {
            println("Start polling server for certificate signing approval.")
            // Poll server to download the signed certificate once request has been approved.
            var idlePeriodDuration: Duration? = null
            while (true) {
                try {
                    val (pollInterval, certificates) = certService.retrieveCertificates(requestId)
                    if (certificates != null) {
                        return certificates
                    }
                    Thread.sleep(pollInterval.toMillis())
                } catch (e: ServiceUnavailableException) {
                    idlePeriodDuration = nextIdleDuration(idlePeriodDuration)
                    if (idlePeriodDuration != null) {
                        Thread.sleep(idlePeriodDuration.toMillis())
                    } else {
                        throw NodeRegistrationException("Compatibility Zone registration service is currently unavailable, "
                                + "try again later!.", e)
                    }
                }
            }
        } catch (certificateRequestException: CertificateRequestException) {
            System.err.println(certificateRequestException.message)
            System.err.println("Please make sure the details in configuration file are correct and try again.")
            System.err.println("Corda node will now terminate.")
            requestIdStore.deleteIfExists()
            throw certificateRequestException
        }
    }

    /**
     * Submit Certificate Signing Request to Certificate signing service if request ID not found in file system.
     * New request ID will be stored in requestId.txt
     * @param publicKey public key for which we need a certificate.
     * @param contentSigner the [ContentSigner] that will sign the CSR.
     * @return Request ID return from the server.
     */
    private fun submitOrResumeCertificateSigningRequest(publicKey: PublicKey, contentSigner: ContentSigner): String {
        try {
            // Retrieve request id from file if exists, else post a request to server.
            return if (!requestIdStore.exists()) {
                val request = X509Utilities.createCertificateSigningRequest(myLegalName.x500Principal, emailAddress, publicKey, contentSigner, certRole)
                val writer = StringWriter()
                JcaPEMWriter(writer).use {
                    it.writeObject(PemObject("CERTIFICATE REQUEST", request.encoded))
                }
                println("Certificate signing request with the following information will be submitted to the Corda certificate signing server.")
                println()
                println("Legal Name: $myLegalName")
                println("Email: $emailAddress")
                println()
                println("Public Key: $publicKey")
                println()
                println("$writer")
                // Post request to signing server via http.
                println("Submitting certificate signing request to Corda certificate signing server.")
                val requestId = certService.submitRequest(request)
                // Persists request ID to file in case of node shutdown.
                requestIdStore.writeLines(listOf(requestId))
                println("Successfully submitted request to Corda certificate signing server, request ID: $requestId.")
                requestId
            } else {
                val requestId = requestIdStore.readLines { it.findFirst().get() }
                println("Resuming from previous certificate signing request, request ID: $requestId.")
                requestId
            }
        } catch (e: Exception) {
            if (e is ConnectException || e is ServiceUnavailableException || e is IOException) {
                throw NodeRegistrationException(e)
            }
            throw e
        }
    }

    protected open fun onSuccess(publicKey: PublicKey, contentSigner: ContentSigner, certificates: List<X509Certificate>, tlsCrlCertificateIssuer: X500Name?) {}

    protected open fun validateAndGetTlsCrlIssuerCert(): X509Certificate? = null

    protected open fun isTlsCrlIssuerCertRequired(): Boolean = false
}

class NodeRegistrationException(
        message: String?,
        cause: Throwable?
) : IOException(message ?: "Unable to contact node registration service", cause)

class NodeRegistrationHelper(
        private val config: NodeConfiguration,
        certService: NetworkRegistrationService, regConfig: NodeRegistrationOption, computeNextIdleDoormanConnectionPollInterval: (Duration?) -> Duration? = FixedPeriodLimitedRetrialStrategy(10, Duration.ofMinutes(1))) :
        NetworkRegistrationHelper(
                config,
                certService,
                regConfig.networkRootTrustStorePath,
                regConfig.networkRootTrustStorePassword,
                CORDA_CLIENT_CA,
                CertRole.NODE_CA,
                computeNextIdleDoormanConnectionPollInterval) {

    companion object {
        val logger = contextLogger()
    }

    override fun onSuccess(publicKey: PublicKey, contentSigner: ContentSigner, certificates: List<X509Certificate>, tlsCrlCertificateIssuer: X500Name?) {
        createSSLKeystore(publicKey, contentSigner, certificates, tlsCrlCertificateIssuer)
        createTruststore(certificates.last())
    }

    private fun createSSLKeystore(publicKey: PublicKey, contentSigner: ContentSigner, certificates: List<X509Certificate>, tlsCertCrlIssuer: X500Name?) {
        config.p2pSslOptions.keyStore.get(createNew = true).update {
            println("Generating SSL certificate for node messaging service.")
            val sslKeyPair = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
            val issuerCertificate = certificates.first()
            val validityWindow = X509Utilities.getCertificateValidityWindow(DEFAULT_VALIDITY_WINDOW.first, DEFAULT_VALIDITY_WINDOW.second, issuerCertificate)

            val sslCert = X509Utilities.createCertificate(
                    CertificateType.TLS,
                    issuerCertificate.issuerX500Principal,
                    publicKey,
                    contentSigner,
                    config.myLegalName.x500Principal,
                    sslKeyPair.public,
                    validityWindow,
                    crlDistPoint = config.tlsCertCrlDistPoint?.toString(),
                    crlIssuer = tlsCertCrlIssuer)
            logger.info("Generated TLS certificate: $sslCert")
            setPrivateKey(CORDA_CLIENT_TLS, sslKeyPair.private, listOf(sslCert) + certificates)
        }
        println("SSL private key and certificate stored in ${config.p2pSslOptions.keyStore.path}.")
    }

    private fun createTruststore(rootCertificate: X509Certificate) {
        // Save root certificates to trust store.
        config.p2pSslOptions.trustStore.get(createNew = true).update {
            if (this.aliases().hasNext()) {
                logger.warn("The node's trust store already exists. The following certificates will be overridden: ${this.aliases().asSequence()}")
            }
            println("Generating trust store for corda node.")
            // Assumes certificate chain always starts with client certificate and end with root certificate.
            setCertificate(CORDA_ROOT_CA, rootCertificate)
            // Copy remaining certificates from the network-trust-store
            rootTrustStore.aliases().asSequence().filter { it != CORDA_ROOT_CA }.forEach {
                val certificate = rootTrustStore.getCertificate(it)
                logger.info("Copying trusted certificate to the node's trust store: Alias: $it, Certificate: $certificate")
                setCertificate(it, certificate)
            }
        }
        println("Node trust store stored in ${config.p2pSslOptions.trustStore.path}.")
    }

    override fun validateAndGetTlsCrlIssuerCert(): X509Certificate? {
        val tlsCertCrlIssuer = config.tlsCertCrlIssuer
        tlsCertCrlIssuer ?: return null
        if (principalMatchesCertificatePrincipal(tlsCertCrlIssuer, rootCert)) {
            return rootCert
        }
        val trustStore = config.p2pSslOptions.trustStore.getOptional()
        return if (trustStore != null) {
            findMatchingCertificate(tlsCertCrlIssuer, trustStore.value)
        } else {
            null
        }
    }

    override fun isTlsCrlIssuerCertRequired(): Boolean {
        return config.tlsCertCrlIssuer != null
    }

    private fun findMatchingCertificate(principal: X500Principal, trustStore: X509KeyStore): X509Certificate? {
        trustStore.aliases().forEach {
            val certificate = trustStore.getCertificate(it)
            if (principalMatchesCertificatePrincipal(principal, certificate)) {
                return certificate
            }
        }
        return null
    }

    private fun principalMatchesCertificatePrincipal(principal: X500Principal, certificate: X509Certificate): Boolean {
        return certificate.subjectX500Principal.isEquivalentTo(principal)
    }
}

private class FixedPeriodLimitedRetrialStrategy(times: Int, private val period: Duration) : (Duration?) -> Duration? {

    init {
        require(times > 0)
    }

    private var counter = times

    override fun invoke(@Suppress("UNUSED_PARAMETER") previousPeriod: Duration?): Duration? {
        synchronized(this) {
            return if (counter-- > 0) period else null
        }
    }
}

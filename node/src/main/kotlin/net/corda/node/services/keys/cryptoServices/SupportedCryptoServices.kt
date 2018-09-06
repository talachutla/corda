package net.corda.node.services.keys.cryptoServices

enum class SupportedCryptoServices {
    BC_SIMPLE // BouncyCastle using Java KeyStores.
    // UTIMACO, // Utimaco HSM.
    // GEMALTO_LUNA, // Gemalto Luna HSM.
    // AZURE_KV // Azure key Vault.
}

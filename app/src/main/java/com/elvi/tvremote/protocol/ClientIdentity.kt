package com.elvi.tvremote.protocol

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** The private key is generated in AndroidKeyStore and never exported to a file. */
internal class ClientIdentity private constructor(
    val certificate: X509Certificate,
    private val privateKey: PrivateKey,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType?.startsWith("RSA") == true) arrayOf(ALIAS) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (keyType?.any { it.startsWith("RSA") } == true) ALIAS else null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = chooseClientAlias(keyType, issuers, null)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) privateKey else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    companion object {
        private const val ALIAS = "elvi.androidtv.client.v2"
        private val lock = Any()

        fun load(): ClientIdentity = synchronized(lock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                val now = System.currentTimeMillis()
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    // Conscrypt hashes TLS transcripts itself, then delegates raw RSA signing.
                    // Android's KeyGenParameterSpec TLS guidance requires DIGEST_NONE.
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512,
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                    )
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setCertificateSubject(X500Principal("CN=Roomote"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
                    .setCertificateNotAfter(Date(now + 25L * 365 * 24 * 60 * 60 * 1000))
                    .setUserAuthenticationRequired(false)
                    .apply {
                        if (Build.VERSION.SDK_INT >= 35) {
                            setMgf1Digests(
                                KeyProperties.DIGEST_SHA256,
                                KeyProperties.DIGEST_SHA384,
                                KeyProperties.DIGEST_SHA512,
                            )
                        }
                    }
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(spec)
                    generateKeyPair()
                }
            }
            ClientIdentity(
                store.getCertificate(ALIAS) as X509Certificate,
                store.getKey(ALIAS, null) as PrivateKey,
            )
        }
    }
}

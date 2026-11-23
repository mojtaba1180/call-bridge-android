package com.example.callbridge.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages SHA-256 certificate fingerprint normalization, constant-time pin verification,
 * and pinned client [SSLContext] creation for local TLS loopback verification.
 */
class CertificatePinningManager(
    private val keyStoreManager: KeyStoreManager
) {
    fun currentServerPin(): String = keyStoreManager.getServerCertificateFingerprint()

    fun verifyFingerprintMatchesServer(suppliedFingerprint: String?): Boolean {
        if (suppliedFingerprint.isNullOrBlank()) return false
        val expected = normalizeFingerprint(currentServerPin())
        val actual = normalizeFingerprint(suppliedFingerprint)
        if (expected.length != 64 || actual.length != 64) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        fun fingerprintOf(cert: X509Certificate): String = KeyStoreManager.computeSha256Fingerprint(cert)

        fun normalizeFingerprint(fp: String): String =
            fp.replace(":", "").replace(" ", "").trim().uppercase()

        fun verifyCertificatePin(cert: X509Certificate, expectedFingerprint: String?): Boolean {
            if (expectedFingerprint.isNullOrBlank()) return false
            val expected = normalizeFingerprint(expectedFingerprint)
            val actual = normalizeFingerprint(fingerprintOf(cert))
            if (expected.length != 64 || actual.length != 64) return false
            return MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                actual.toByteArray(Charsets.UTF_8)
            )
        }

        /**
         * Creates a strict certificate-pinned client [SSLContext] that ONLY trusts the exact
         * leaf X.509 certificate matching [expectedFingerprint] (never trusts arbitrary certs).
         */
        fun createPinnedClientSslContext(expectedFingerprint: String): SSLContext {
            val pinningTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    throw CertificateException("Client authentication not used")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull()
                        ?: throw CertificateException("Empty server certificate chain")
                    leaf.checkValidity()
                    if (!verifyCertificatePin(leaf, expectedFingerprint)) {
                        throw CertificateException("Server certificate SHA-256 fingerprint mismatch")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            return SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(pinningTrustManager), SecureRandom())
            }
        }
    }
}

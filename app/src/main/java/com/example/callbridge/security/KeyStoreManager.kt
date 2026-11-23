package com.example.callbridge.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.callbridge.core.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class ServerTlsMaterial(
    val sslContext: SSLContext,
    val certificate: X509Certificate,
    val sha256Fingerprint: String
)

/**
 * Manages BridgeLink's persistent on-device TLS X.509 certificate and cryptographic key protection.
 *
 * - Generates a standard RSA-2048 self-signed X.509v3 certificate using JCA (`SHA256withRSA`) with
 *   zero third-party crypto dependencies.
 * - Protects the keystore password using an Android Keystore AES-256/GCM master key on API 23+
 *   (with secure random fallback on API 21–22 / JVM test environments).
 * - Exposes a stable SHA-256 DER fingerprint for QR code certificate pinning by the iOS client.
 */
class KeyStoreManager(context: Context) {

    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()
    private val lock = Any()

    private var cachedMaterial: ServerTlsMaterial? = null

    fun getOrCreateTlsMaterial(): ServerTlsMaterial = synchronized(lock) {
        cachedMaterial?.let { return it }

        val ksFile = File(appContext.filesDir, KEYSTORE_FILE_NAME)
        val password = loadOrCreateKeystorePassword()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())

        val material = if (ksFile.exists()) {
            try {
                FileInputStream(ksFile).use { fis ->
                    keyStore.load(fis, password)
                }
                val cert = keyStore.getCertificate(TLS_KEY_ALIAS) as? X509Certificate
                val key = keyStore.getKey(TLS_KEY_ALIAS, password) as? PrivateKey
                if (cert != null && key != null && cert.notAfter.after(Date())) {
                    buildMaterial(keyStore, password, cert)
                } else {
                    generateAndPersistMaterial(ksFile, password)
                }
            } catch (t: Throwable) {
                Logger.w("Security", "Regenerating TLS keystore after load failure: ${t.message}")
                generateAndPersistMaterial(ksFile, password)
            }
        } else {
            generateAndPersistMaterial(ksFile, password)
        }

        cachedMaterial = material
        material
    }

    fun getServerCertificateFingerprint(): String = getOrCreateTlsMaterial().sha256Fingerprint

    fun regenerateCertificate(): ServerTlsMaterial = synchronized(lock) {
        val ksFile = File(appContext.filesDir, KEYSTORE_FILE_NAME)
        val password = loadOrCreateKeystorePassword()
        val material = generateAndPersistMaterial(ksFile, password)
        cachedMaterial = material
        Logger.i("Security", "Regenerated TLS X.509 certificate")
        material
    }

    private fun generateAndPersistMaterial(ksFile: File, password: CharArray): ServerTlsMaterial {
        val keyPair = generateRsaKeyPair()
        val cert = generateSelfSignedX509Cert(keyPair, commonName = "BridgeLink Local Companion")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry(TLS_KEY_ALIAS, keyPair.private, password, arrayOf(cert))
        FileOutputStream(ksFile).use { fos ->
            keyStore.store(fos, password)
        }
        Logger.i("Security", "Generated and persisted on-device TLS X.509 certificate")
        return buildMaterial(keyStore, password, cert)
    }

    private fun buildMaterial(
        keyStore: KeyStore,
        password: CharArray,
        cert: X509Certificate
    ): ServerTlsMaterial {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, secureRandom)
        return ServerTlsMaterial(
            sslContext = sslContext,
            certificate = cert,
            sha256Fingerprint = computeSha256Fingerprint(cert)
        )
    }

    private fun generateRsaKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, secureRandom)
        return gen.generateKeyPair()
    }

    private fun loadOrCreateKeystorePassword(): CharArray {
        val wrappedFile = File(appContext.filesDir, WRAPPED_PASS_FILE)
        if (wrappedFile.exists()) {
            val loaded = runCatching { decryptWithAndroidKeystore(wrappedFile.readBytes()) }.getOrNull()
            if (loaded != null && loaded.isNotEmpty()) {
                return String(loaded, Charsets.UTF_8).toCharArray()
            }
        }
        val randomBytes = ByteArray(24).also { secureRandom.nextBytes(it) }
        val passString = randomBytes.joinToString("") { "%02x".format(it) }
        val encrypted = runCatching { encryptWithAndroidKeystore(passString.toByteArray(Charsets.UTF_8)) }
            .getOrElse { passString.toByteArray(Charsets.UTF_8) }
        runCatching { wrappedFile.writeBytes(encrypted) }
        return passString.toCharArray()
    }

    private fun encryptWithAndroidKeystore(plain: ByteArray): ByteArray {
        val secretKey = getOrCreateMasterAesKey() ?: return plain
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(0xA5) // Marker: encrypted with AndroidKeyStore
            write(iv.size)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    private fun decryptWithAndroidKeystore(blob: ByteArray): ByteArray {
        if (blob.size < 3 || blob[0] != 0xA5.toByte()) return blob
        val secretKey = getOrCreateMasterAesKey() ?: return blob
        val ivLen = blob[1].toInt() and 0xFF
        if (blob.size <= 2 + ivLen) return blob
        val iv = blob.copyOfRange(2, 2 + ivLen)
        val ciphertext = blob.copyOfRange(2 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateMasterAesKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            (ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey) ?: run {
                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        }.getOrNull()
    }

    companion object {
        private const val KEYSTORE_FILE_NAME = "bridgelink_tls.ks"
        private const val WRAPPED_PASS_FILE = "bridgelink_tls.kpass"
        private const val TLS_KEY_ALIAS = "bridgelink_wss_tls"
        private const val MASTER_KEY_ALIAS = "bridgelink_master_aes"

        fun computeSha256Fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString(":") { "%02X".format(it) }
        }

        /**
         * Standards-compliant pure-JCA ASN.1 DER X.509v3 self-signed certificate builder
         * using `SHA256withRSA` (OID 1.2.840.113549.1.1.11).
         */
        fun generateSelfSignedX509Cert(
            keyPair: KeyPair,
            commonName: String,
            validityDays: Long = 825L
        ): X509Certificate {
            val nowMs = System.currentTimeMillis()
            val notBefore = Date(nowMs - 60_000L)
            val notAfter = Date(nowMs + validityDays * 24L * 60L * 60L * 1000L)
            val serial = BigInteger(128, SecureRandom()).abs().max(BigInteger.ONE)

            val sha256WithRsaAlgId = derSequence(
                derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)),
                derNull()
            )
            val name = buildCommonName(commonName)
            val validity = derSequence(derUtcTime(notBefore), derUtcTime(notAfter))
            val subjectPublicKeyInfo = keyPair.public.encoded // Already DER SubjectPublicKeyInfo

            val versionV3 = derExplicitTag(0, derInteger(BigInteger.valueOf(2)))
            val tbsCert = derSequence(
                versionV3,
                derInteger(serial),
                sha256WithRsaAlgId,
                name,
                validity,
                name,
                subjectPublicKeyInfo
            )

            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbsCert)
            }.sign()

            val fullCertDer = derSequence(
                tbsCert,
                sha256WithRsaAlgId,
                derBitString(signature)
            )

            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
        }

        private fun buildCommonName(cn: String): ByteArray {
            val cnOid = derOid(intArrayOf(2, 5, 4, 3))
            val cnUtf8 = derTag(0x0C, cn.toByteArray(Charsets.UTF_8))
            val atv = derSequence(cnOid, cnUtf8)
            val rdn = derTag(0x31, atv)
            return derSequence(rdn)
        }

        private fun derUtcTime(date: Date): ByteArray {
            val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return derTag(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun derSequence(vararg items: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            items.forEach { out.write(it) }
            return derTag(0x30, out.toByteArray())
        }

        private fun derExplicitTag(tagNumber: Int, content: ByteArray): ByteArray {
            return derTag(0xA0 or tagNumber, content)
        }

        private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

        private fun derBitString(bytes: ByteArray): ByteArray {
            val out = ByteArray(bytes.size + 1)
            out[0] = 0x00 // 0 unused bits
            System.arraycopy(bytes, 0, out, 1, bytes.size)
            return derTag(0x03, out)
        }

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derOid(arcs: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * 40 + arcs[1])
            for (i in 2 until arcs.size) {
                var v = arcs[i]
                val tmp = IntArray(5)
                var count = 0
                do {
                    tmp[count++] = v and 0x7F
                    v = v ushr 7
                } while (v > 0)
                for (j in count - 1 downTo 0) {
                    val b = if (j > 0) tmp[j] or 0x80 else tmp[j]
                    out.write(b)
                }
            }
            return derTag(0x06, out.toByteArray())
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val len = content.size
            when {
                len < 128 -> out.write(len)
                len < 256 -> {
                    out.write(0x81)
                    out.write(len)
                }
                else -> {
                    out.write(0x82)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}

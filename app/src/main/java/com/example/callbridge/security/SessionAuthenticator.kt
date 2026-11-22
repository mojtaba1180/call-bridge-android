package com.example.callbridge.security

import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AuthenticatedSession(
    val sessionToken: String,
    val deviceId: String,
    val bleAddress: String?,
    val establishedAtMs: Long
)

/**
 * Manages per-connection session tokens and authentication checks for both BLE GATT commands
 * and the local TCP control server.
 *
 * Uses standard JCA primitives (SecureRandom, MessageDigest.isEqual, HmacSHA256) with brute-force
 * cooldown protection. Never invents custom cryptographic algorithms.
 */
class SessionAuthenticator(
    private val pairingManager: PairingManager
) {
    private val secureRandom = SecureRandom()
    private val lock = Any()

    private val _activeSession = MutableStateFlow<AuthenticatedSession?>(null)
    val activeSession: StateFlow<AuthenticatedSession?> = _activeSession.asStateFlow()

    private var failedAttempts = 0
    private var cooldownUntilMs = 0L

    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        nowMs < cooldownUntilMs
    }

    /**
     * Authenticates a previously paired device and issues a fresh session token.
     */
    fun authenticatePairedDevice(
        deviceId: String?,
        bleAddress: String?,
        nowMs: Long = System.currentTimeMillis()
    ): AuthenticatedSession? = synchronized(lock) {
        if (nowMs < cooldownUntilMs) {
            Logger.w("Security", "Authentication blocked during cooldown window")
            return null
        }

        if (!pairingManager.isDevicePaired(deviceId)) {
            recordFailure(nowMs)
            Logger.w("Security", "Authentication rejected: device is not paired")
            return null
        }

        failedAttempts = 0
        cooldownUntilMs = 0L

        val tokenBytes = ByteArray(24).also { secureRandom.nextBytes(it) }
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        val session = AuthenticatedSession(
            sessionToken = token,
            deviceId = deviceId!!,
            bleAddress = bleAddress,
            establishedAtMs = nowMs
        )
        _activeSession.value = session
        Logger.i("Security", "iPhone session authenticated")
        session
    }

    /**
     * Verifies whether a sensitive command is authorized via active session token
     * (and optionally bound BLE address).
     */
    fun isAuthorized(sessionToken: String?, bleAddress: String? = null): Boolean = synchronized(lock) {
        val current = _activeSession.value ?: return false
        if (sessionToken.isNullOrBlank()) return false
        val tokenValid = MessageDigest.isEqual(
            current.sessionToken.toByteArray(Charsets.UTF_8),
            sessionToken.toByteArray(Charsets.UTF_8)
        )
        if (!tokenValid) return false
        if (bleAddress != null && current.bleAddress != null && current.bleAddress != bleAddress) {
            return false
        }
        true
    }

    /**
     * Computes standard HMAC-SHA256 digest for message integrity verification when a shared key is provisioned.
     */
    fun computeHmacSha256(key: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun invalidateSession(reason: String = "disconnected") = synchronized(lock) {
        if (_activeSession.value != null) {
            _activeSession.value = null
            Logger.i("Security", "Session invalidated ($reason)")
        }
    }

    private fun recordFailure(nowMs: Long) {
        failedAttempts++
        if (failedAttempts >= AppConfig.MAX_AUTH_FAILURES_BEFORE_COOLDOWN) {
            cooldownUntilMs = nowMs + AppConfig.AUTH_COOLDOWN_MS
            failedAttempts = 0
            Logger.w("Security", "Too many failed auth attempts; cooldown activated")
        }
    }
}

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
import kotlin.math.abs

data class AuthenticatedSession(
    val sessionToken: String,
    val deviceId: String,
    val bleAddress: String?,
    val establishedAtMs: Long,
    val expiresAtMs: Long
)

/**
 * Manages authenticated companion sessions with session expiration, timestamp replay protection,
 * request idempotency tracking, constant-time token comparison, and brute-force cooldowns.
 */
class SessionAuthenticator(
    private val pairingManager: PairingManager
) {
    private val secureRandom = SecureRandom()
    private val lock = Any()

    private val _activeSession = MutableStateFlow<AuthenticatedSession?>(null)
    val activeSession: StateFlow<AuthenticatedSession?> = _activeSession.asStateFlow()

    private val processedIdempotencyKeys = LinkedHashSet<String>()
    private var failedAttempts = 0
    private var cooldownUntilMs = 0L

    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        nowMs < cooldownUntilMs
    }

    fun authenticatePairedDevice(
        deviceId: String?,
        bleAddress: String?,
        nowMs: Long = System.currentTimeMillis()
    ): AuthenticatedSession? = synchronized(lock) {
        if (nowMs < cooldownUntilMs) {
            Logger.w("Security", "Authentication blocked during brute-force cooldown")
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
            establishedAtMs = nowMs,
            expiresAtMs = nowMs + AppConfig.SESSION_TTL_MS
        )
        _activeSession.value = session
        Logger.i("Security", "Companion session authenticated (expires in 12h)")
        session
    }

    fun isAuthorized(
        sessionToken: String?,
        bleAddress: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        val current = _activeSession.value ?: return false
        if (nowMs > current.expiresAtMs) {
            _activeSession.value = null
            Logger.w("Security", "Active session expired; re-authentication required")
            return false
        }
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
     * Checks whether a command timestamp lies within the allowed replay protection window.
     * Returns true when timestamp is null (legacy clients) or within +/- 120 seconds.
     */
    fun isTimestampFresh(commandTimestampMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (commandTimestampMs == null) return true
        return abs(nowMs - commandTimestampMs) <= AppConfig.REPLAY_TIMESTAMP_WINDOW_MS
    }

    /**
     * Records an idempotency key; returns false if this key was already executed recently (replay/duplicate).
     */
    fun claimIdempotencyKey(idempotencyKey: String?): Boolean = synchronized(lock) {
        if (idempotencyKey.isNullOrBlank()) return true
        if (processedIdempotencyKeys.contains(idempotencyKey)) {
            return false
        }
        if (processedIdempotencyKeys.size >= MAX_TRACKED_KEYS) {
            val first = processedIdempotencyKeys.firstOrNull()
            if (first != null) processedIdempotencyKeys.remove(first)
        }
        processedIdempotencyKeys.add(idempotencyKey)
        true
    }

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

    companion object {
        private const val MAX_TRACKED_KEYS = 256
    }
}

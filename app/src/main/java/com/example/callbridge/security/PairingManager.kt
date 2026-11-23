package com.example.callbridge.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.PairedDeviceDao
import com.example.callbridge.database.entities.PairedDeviceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val pairedAtMs: Long,
    val bleAddress: String?
)

data class PendingPairingRequest(
    val deviceId: String,
    val deviceName: String,
    val bleAddress: String?,
    val pinCode: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

sealed class PairingState {
    data object Idle : PairingState()
    data class PairingWindowOpen(
        val pinCode: String,
        val qrPayload: PairingQrPayload,
        val expiresAtMs: Long
    ) : PairingState()
    data class AwaitingUserApproval(val request: PendingPairingRequest) : PairingState()
    data class Paired(val device: PairedDevice) : PairingState()
}

/**
 * Manages explicit user-initiated QR & PIN pairing, Android device identity,
 * certificate fingerprint pinning metadata, Room persistence, and revocation.
 */
class PairingManager(
    context: Context,
    private val keyStoreManager: KeyStoreManager? = null,
    private val pairedDeviceDao: PairedDeviceDao? = null
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val lock = Any()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val localDeviceId: String = loadOrCreateLocalDeviceId()
    val localDeviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "BridgeLink Android" }

    private val _pairedDevice = MutableStateFlow<PairedDevice?>(loadPairedDevice())
    val pairedDevice: StateFlow<PairedDevice?> = _pairedDevice.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(
        _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
    )
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var activePairingPin: String? = null
    private var activePairingToken: String? = null
    private var activePairingWindowExpiry: Long = 0L
    private var pendingRequest: PendingPairingRequest? = null

    /**
     * Generates a fresh one-time QR payload + 6-digit verification PIN expiring in 120 seconds.
     */
    fun openPairingWindow(currentLocalIp: String? = null): PairingQrPayload = synchronized(lock) {
        val pin = String.format("%06d", secureRandom.nextInt(1_000_000))
        val tokenBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val token = tokenBytes.toHexString()
        val now = System.currentTimeMillis()
        val expiresAt = now + AppConfig.PAIRING_QR_TTL_MS
        val certSha = keyStoreManager?.getServerCertificateFingerprint() ?: "UNINITIALIZED"

        val payload = PairingQrPayload(
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            token = token,
            pinCode = pin,
            certSha256 = certSha,
            wssHost = currentLocalIp,
            wssPort = AppConfig.DEFAULT_WSS_PORT,
            expiresAtMs = expiresAt
        )

        activePairingPin = pin
        activePairingToken = token
        activePairingWindowExpiry = expiresAt
        pendingRequest = null
        _pairingState.value = PairingState.PairingWindowOpen(pin, payload, expiresAt)
        Logger.i("Security", "One-time QR pairing window opened (120s)")
        payload
    }

    fun closePairingWindow() = synchronized(lock) {
        activePairingPin = null
        activePairingToken = null
        activePairingWindowExpiry = 0L
        pendingRequest = null
        _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
    }

    /**
     * Validates an incoming PAIR_REQUEST (with optional one-time QR token) and requires explicit
     * user confirmation on Android before trusting the remote iPhone.
     */
    fun registerIncomingPairRequest(
        deviceId: String,
        deviceName: String?,
        bleAddress: String?,
        suppliedToken: String? = null
    ): PendingPairingRequest? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val expectedToken = activePairingToken
        if (expectedToken != null && !suppliedToken.isNullOrBlank()) {
            if (now > activePairingWindowExpiry) {
                closePairingWindow()
                Logger.w("Security", "Rejected expired QR pairing token")
                return null
            }
            val tokenMatch = MessageDigest.isEqual(
                expectedToken.toByteArray(Charsets.UTF_8),
                suppliedToken.trim().toByteArray(Charsets.UTF_8)
            )
            if (!tokenMatch) {
                Logger.w("Security", "Rejected mismatched QR pairing token")
                return null
            }
        }

        val pin = if (activePairingPin != null && now < activePairingWindowExpiry) {
            activePairingPin!!
        } else {
            String.format("%06d", secureRandom.nextInt(1_000_000)).also {
                activePairingPin = it
                activePairingWindowExpiry = now + AppConfig.PAIRING_QR_TTL_MS
            }
        }

        val request = PendingPairingRequest(
            deviceId = deviceId,
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: "iPhone",
            bleAddress = bleAddress,
            pinCode = pin,
            createdAtMs = now,
            expiresAtMs = now + AppConfig.PAIRING_QR_TTL_MS
        )
        pendingRequest = request
        _pairingState.value = PairingState.AwaitingUserApproval(request)
        Logger.i("Security", "Incoming pairing request from ${request.deviceName} awaiting user approval")
        request
    }

    fun verifyPinForPairing(
        deviceId: String,
        deviceName: String?,
        bleAddress: String?,
        suppliedPin: String
    ): Boolean = synchronized(lock) {
        val expectedPin = activePairingPin ?: return false
        if (System.currentTimeMillis() > activePairingWindowExpiry) {
            closePairingWindow()
            return false
        }
        val matches = MessageDigest.isEqual(
            expectedPin.toByteArray(Charsets.UTF_8),
            suppliedPin.trim().toByteArray(Charsets.UTF_8)
        )
        if (!matches) {
            Logger.w("Security", "Rejected PAIR_VERIFY with mismatched PIN")
            return false
        }
        val request = PendingPairingRequest(
            deviceId = deviceId,
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: "iPhone",
            bleAddress = bleAddress,
            pinCode = expectedPin,
            createdAtMs = System.currentTimeMillis(),
            expiresAtMs = activePairingWindowExpiry
        )
        pendingRequest = request
        _pairingState.value = PairingState.AwaitingUserApproval(request)
        Logger.i("Security", "PIN verified for ${request.deviceName}; awaiting explicit Android user approval")
        true
    }

    fun approvePendingPairing(): PairedDevice? = synchronized(lock) {
        val req = pendingRequest ?: return null
        if (System.currentTimeMillis() > req.expiresAtMs) {
            pendingRequest = null
            _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
            Logger.w("Security", "Pending pairing request expired before approval")
            return null
        }

        val secretBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val secretHash = sha256Hex(secretBytes.toHexString())
        val now = System.currentTimeMillis()
        val paired = PairedDevice(
            deviceId = req.deviceId,
            deviceName = req.deviceName,
            pairedAtMs = now,
            bleAddress = req.bleAddress
        )
        savePairedDevice(paired, secretHash)
        pendingRequest = null
        activePairingPin = null
        activePairingToken = null
        activePairingWindowExpiry = 0L
        _pairedDevice.value = paired
        _pairingState.value = PairingState.Paired(paired)
        Logger.i("Security", "Pairing successful with ${paired.deviceName}")
        paired
    }

    fun rejectPendingPairing() = synchronized(lock) {
        pendingRequest = null
        activePairingPin = null
        activePairingToken = null
        activePairingWindowExpiry = 0L
        _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
        Logger.i("Security", "User rejected pairing request")
    }

    fun unpair() = synchronized(lock) {
        val oldId = _pairedDevice.value?.deviceId
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_PAIRED_AT)
            .remove(KEY_BLE_ADDRESS)
            .remove(KEY_SHARED_SECRET_HASH)
            .apply()
        if (oldId != null && pairedDeviceDao != null) {
            persistenceScope.launch {
                runCatching { pairedDeviceDao.markRevoked(oldId) }
            }
        }
        pendingRequest = null
        activePairingPin = null
        activePairingToken = null
        activePairingWindowExpiry = 0L
        _pairedDevice.value = null
        _pairingState.value = PairingState.Idle
        Logger.i("Security", "Pairing revoked and cleared")
    }

    fun isDevicePaired(deviceId: String?): Boolean {
        if (deviceId.isNullOrBlank()) return false
        val current = _pairedDevice.value ?: return false
        return MessageDigest.isEqual(
            current.deviceId.toByteArray(Charsets.UTF_8),
            deviceId.toByteArray(Charsets.UTF_8)
        )
    }

    internal fun getStoredSharedSecretHash(): String? = prefs.getString(KEY_SHARED_SECRET_HASH, null)

    private fun savePairedDevice(device: PairedDevice, secretHash: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, device.deviceId)
            .putString(KEY_DEVICE_NAME, device.deviceName)
            .putLong(KEY_PAIRED_AT, device.pairedAtMs)
            .putString(KEY_BLE_ADDRESS, device.bleAddress)
            .putString(KEY_SHARED_SECRET_HASH, secretHash)
            .apply()

        val dao = pairedDeviceDao ?: return
        val fp = keyStoreManager?.getServerCertificateFingerprint() ?: ""
        persistenceScope.launch {
            runCatching {
                dao.upsert(
                    PairedDeviceEntity(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        bleAddress = device.bleAddress,
                        sharedSecretHash = secretHash,
                        certFingerprint = fp,
                        pairedAtMs = device.pairedAtMs,
                        lastSeenAtMs = device.pairedAtMs,
                        revoked = false
                    )
                )
            }
        }
    }

    private fun loadPairedDevice(): PairedDevice? {
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, "iPhone") ?: "iPhone"
        val pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L)
        val bleAddress = prefs.getString(KEY_BLE_ADDRESS, null)
        return PairedDevice(id, name, pairedAt, bleAddress)
    }

    private fun loadOrCreateLocalDeviceId(): String {
        val existing = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID().toString().take(12)}"
        prefs.edit().putString(KEY_LOCAL_DEVICE_ID, created).apply()
        return created
    }

    companion object {
        private const val KEY_LOCAL_DEVICE_ID = "local_android_device_id"
        private const val KEY_DEVICE_ID = "paired_device_id"
        private const val KEY_DEVICE_NAME = "paired_device_name"
        private const val KEY_PAIRED_AT = "paired_at_ms"
        private const val KEY_BLE_ADDRESS = "paired_ble_address"
        private const val KEY_SHARED_SECRET_HASH = "paired_secret_hash"

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return digest.toHexString()
        }

        private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    }
}

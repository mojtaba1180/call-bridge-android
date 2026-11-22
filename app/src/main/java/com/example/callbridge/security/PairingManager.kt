package com.example.callbridge.security

import android.content.Context
import android.content.SharedPreferences
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

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
    data class PairingWindowOpen(val pinCode: String, val expiresAtMs: Long) : PairingState()
    data class AwaitingUserApproval(val request: PendingPairingRequest) : PairingState()
    data class Paired(val device: PairedDevice) : PairingState()
}

/**
 * Application-level pairing handshake manager requiring explicit user approval on Android
 * before trusting a remote iPhone for sensitive telephony commands.
 *
 * Uses cryptographically secure random PINs and SHA-256 secret derivation stored in
 * MODE_PRIVATE SharedPreferences (no custom cryptographic algorithms).
 */
class PairingManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val lock = Any()

    private val _pairedDevice = MutableStateFlow<PairedDevice?>(loadPairedDevice())
    val pairedDevice: StateFlow<PairedDevice?> = _pairedDevice.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(
        _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
    )
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var activePairingPin: String? = null
    private var activePairingWindowExpiry: Long = 0L
    private var pendingRequest: PendingPairingRequest? = null

    /**
     * Opens a temporary 60-second pairing window initiated explicitly by the Android user
     * and generates a 6-digit numeric PIN for out-of-band verification on the iPhone.
     */
    fun openPairingWindow(): String = synchronized(lock) {
        val codeInt = secureRandom.nextInt(1_000_000)
        val pin = String.format("%06d", codeInt)
        val now = System.currentTimeMillis()
        val expiresAt = now + AppConfig.PAIRING_REQUEST_TIMEOUT_MS
        activePairingPin = pin
        activePairingWindowExpiry = expiresAt
        pendingRequest = null
        _pairingState.value = PairingState.PairingWindowOpen(pin, expiresAt)
        Logger.i("Security", "Pairing window opened (60s) awaiting iPhone handshake")
        pin
    }

    fun closePairingWindow() = synchronized(lock) {
        activePairingPin = null
        activePairingWindowExpiry = 0L
        pendingRequest = null
        _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
    }

    /**
     * Called when a BLE client sends PAIR_REQUEST. Requires an open pairing window or
     * creates a pending approval prompt that the Android user must explicitly confirm.
     */
    fun registerIncomingPairRequest(
        deviceId: String,
        deviceName: String?,
        bleAddress: String?
    ): PendingPairingRequest = synchronized(lock) {
        val now = System.currentTimeMillis()
        val pin = if (activePairingPin != null && now < activePairingWindowExpiry) {
            activePairingPin!!
        } else {
            String.format("%06d", secureRandom.nextInt(1_000_000)).also {
                activePairingPin = it
                activePairingWindowExpiry = now + AppConfig.PAIRING_REQUEST_TIMEOUT_MS
            }
        }
        val request = PendingPairingRequest(
            deviceId = deviceId,
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: "iPhone",
            bleAddress = bleAddress,
            pinCode = pin,
            createdAtMs = now,
            expiresAtMs = now + AppConfig.PAIRING_REQUEST_TIMEOUT_MS
        )
        pendingRequest = request
        _pairingState.value = PairingState.AwaitingUserApproval(request)
        Logger.i("Security", "Incoming pairing request from ${request.deviceName} awaiting user approval")
        request
    }

    /**
     * Explicitly approves the pending pairing request after user confirmation in UI.
     */
    fun approvePendingPairing(): PairedDevice? = synchronized(lock) {
        val req = pendingRequest ?: return null
        if (System.currentTimeMillis() > req.expiresAtMs) {
            pendingRequest = null
            _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
            Logger.w("Security", "Pending pairing request expired before approval")
            return null
        }

        val secretBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val secretHex = secretBytes.toHexString()
        val paired = PairedDevice(
            deviceId = req.deviceId,
            deviceName = req.deviceName,
            pairedAtMs = System.currentTimeMillis(),
            bleAddress = req.bleAddress
        )
        savePairedDevice(paired, secretHex)
        pendingRequest = null
        activePairingPin = null
        activePairingWindowExpiry = 0L
        _pairedDevice.value = paired
        _pairingState.value = PairingState.Paired(paired)
        Logger.i("Security", "User approved pairing for ${paired.deviceName}")
        paired
    }

    /**
     * Verifies a PAIR_VERIFY command containing the user-displayed PIN code.
     * Succeeds only when the pairing window is open and PIN matches in constant time.
     */
    fun verifyPinForPairing(deviceId: String, deviceName: String?, bleAddress: String?, suppliedPin: String): Boolean =
        synchronized(lock) {
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

            // Still require explicit user approval if pendingRequest was not already approved
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
            Logger.i("Security", "PIN verified for ${request.deviceName}; awaiting final Android user approval")
            true
        }

    fun rejectPendingPairing() = synchronized(lock) {
        pendingRequest = null
        activePairingPin = null
        activePairingWindowExpiry = 0L
        _pairingState.value = _pairedDevice.value?.let { PairingState.Paired(it) } ?: PairingState.Idle
        Logger.i("Security", "User rejected incoming pairing request")
    }

    fun unpair() = synchronized(lock) {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_PAIRED_AT)
            .remove(KEY_BLE_ADDRESS)
            .remove(KEY_SHARED_SECRET_HASH)
            .apply()
        pendingRequest = null
        activePairingPin = null
        activePairingWindowExpiry = 0L
        _pairedDevice.value = null
        _pairingState.value = PairingState.Idle
        Logger.i("Security", "Paired iPhone cleared")
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

    private fun savePairedDevice(device: PairedDevice, rawSecretHex: String) {
        val hash = sha256Hex(rawSecretHex)
        prefs.edit()
            .putString(KEY_DEVICE_ID, device.deviceId)
            .putString(KEY_DEVICE_NAME, device.deviceName)
            .putLong(KEY_PAIRED_AT, device.pairedAtMs)
            .putString(KEY_BLE_ADDRESS, device.bleAddress)
            .putString(KEY_SHARED_SECRET_HASH, hash)
            .apply()
    }

    private fun loadPairedDevice(): PairedDevice? {
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, "iPhone") ?: "iPhone"
        val pairedAt = prefs.getLong(KEY_PAIRED_AT, 0L)
        val bleAddress = prefs.getString(KEY_BLE_ADDRESS, null)
        return PairedDevice(id, name, pairedAt, bleAddress)
    }

    companion object {
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

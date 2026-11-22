package com.example.callbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AdvertisingStatus {
    INACTIVE,
    STARTING,
    ACTIVE,
    UNSUPPORTED,
    PERMISSION_MISSING,
    BLUETOOTH_DISABLED,
    FAILED
}

/**
 * Manages BLE Peripheral advertising with hardware support verification
 * (`BluetoothAdapter.isMultipleAdvertisementSupported()`) and exponential backoff retry.
 */
class BleAdvertisingManager(
    private val context: Context,
    private val adapterProvider: () -> BluetoothAdapter?
) {
    private val _status = MutableStateFlow(AdvertisingStatus.INACTIVE)
    val status: StateFlow<AdvertisingStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var retryJob: Job? = null
    private var currentBackoffMs = AppConfig.INITIAL_BACKOFF_MS
    private var activeCallback: AdvertiseCallback? = null

    fun isPeripheralAdvertisingSupported(): Boolean {
        val adapter = adapterProvider() ?: return false
        return try {
            adapter.isMultipleAdvertisementSupported
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(scope: CoroutineScope): Boolean {
        if (_status.value == AdvertisingStatus.ACTIVE || _status.value == AdvertisingStatus.STARTING) {
            return true
        }

        if (!BlePermissions.hasAdvertisePermission(context)) {
            _status.value = AdvertisingStatus.PERMISSION_MISSING
            _lastError.value = "BLUETOOTH_ADVERTISE permission missing"
            Logger.w("BLE", "Cannot start advertising: permission missing")
            return false
        }

        val adapter = adapterProvider()
        if (adapter == null) {
            _status.value = AdvertisingStatus.UNSUPPORTED
            _lastError.value = "Bluetooth adapter unavailable"
            Logger.w("BLE", "Bluetooth adapter unavailable")
            return false
        }

        if (!adapter.isEnabled) {
            _status.value = AdvertisingStatus.BLUETOOTH_DISABLED
            _lastError.value = "Bluetooth is turned off"
            Logger.w("BLE", "Cannot start advertising: Bluetooth is disabled")
            return false
        }

        if (!adapter.isMultipleAdvertisementSupported) {
            _status.value = AdvertisingStatus.UNSUPPORTED
            _lastError.value = "BLE peripheral advertising is not supported by this device chipset"
            Logger.w("BLE", "BLE peripheral advertising unsupported (isMultipleAdvertisementSupported=false)")
            return false
        }

        val bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            _status.value = AdvertisingStatus.UNSUPPORTED
            _lastError.value = "BluetoothLeAdvertiser returned null"
            Logger.w("BLE", "BluetoothLeAdvertiser unavailable")
            return false
        }

        advertiser = bleAdvertiser
        _status.value = AdvertisingStatus.STARTING
        _lastError.value = null

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                currentBackoffMs = AppConfig.INITIAL_BACKOFF_MS
                _status.value = AdvertisingStatus.ACTIVE
                _lastError.value = null
                Logger.i("BLE", "BLE advertising started")
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = mapErrorCode(errorCode)
                _status.value = AdvertisingStatus.FAILED
                _lastError.value = reason
                Logger.e("BLE", "BLE advertising failed: $reason (code=$errorCode)")
                scheduleRetry(scope)
            }
        }
        activeCallback = callback

        return try {
            bleAdvertiser.startAdvertising(settings, advertiseData, scanResponse, callback)
            true
        } catch (se: SecurityException) {
            _status.value = AdvertisingStatus.PERMISSION_MISSING
            _lastError.value = "SecurityException starting advertising: ${se.message}"
            Logger.e("BLE", "SecurityException starting BLE advertising", se)
            false
        } catch (t: Throwable) {
            _status.value = AdvertisingStatus.FAILED
            _lastError.value = t.message
            Logger.e("BLE", "Unexpected error starting BLE advertising", t)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        retryJob?.cancel()
        retryJob = null
        currentBackoffMs = AppConfig.INITIAL_BACKOFF_MS

        val cb = activeCallback
        val adv = advertiser
        activeCallback = null
        if (cb != null && adv != null && BlePermissions.hasAdvertisePermission(context)) {
            runCatching { adv.stopAdvertising(cb) }
                .onFailure { Logger.w("BLE", "Error stopping advertising: ${it.message}") }
        }
        if (_status.value != AdvertisingStatus.UNSUPPORTED) {
            _status.value = AdvertisingStatus.INACTIVE
        }
        Logger.i("BLE", "BLE advertising stopped")
    }

    private fun scheduleRetry(scope: CoroutineScope) {
        retryJob?.cancel()
        val delayMs = currentBackoffMs
        currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(AppConfig.MAX_BACKOFF_MS)
        retryJob = scope.launch {
            Logger.i("BLE", "Retrying BLE advertising in ${delayMs}ms")
            delay(delayMs)
            startAdvertising(scope)
        }
    }

    companion object {
        fun mapErrorCode(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertise data too large (>31 bytes)"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many BLE advertisers active"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth stack internal error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising feature unsupported"
            else -> "Unknown advertise error ($code)"
        }
    }
}

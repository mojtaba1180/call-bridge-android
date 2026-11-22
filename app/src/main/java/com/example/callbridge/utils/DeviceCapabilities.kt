package com.example.callbridge.utils

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import com.example.callbridge.audio.AudioCapabilityChecker
import com.example.callbridge.audio.AudioCapabilityReport
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.DefaultDialerManager

data class DeviceCapabilitySummary(
    val apiLevel: Int,
    val androidVersionLabel: String,
    val hasBleHardware: Boolean,
    val hasBlePeripheralSupport: Boolean,
    val hasTelephonyHardware: Boolean,
    val inCallServiceApiSupported: Boolean,
    val isDefaultDialer: Boolean,
    val callControlCapability: CallControlCapability,
    val audioReport: AudioCapabilityReport
)

/**
 * Aggregates hardware, OS API, Telecom, BLE Peripheral, and Audio capability probes
 * so the dashboard and diagnostics present accurate device-specific limitations.
 */
class DeviceCapabilities(
    context: Context,
    private val defaultDialerManager: DefaultDialerManager,
    private val audioCapabilityChecker: AudioCapabilityChecker
) {
    private val appContext = context.applicationContext

    fun inspect(callControlCapability: CallControlCapability): DeviceCapabilitySummary {
        val pm = appContext.packageManager
        val hasBle = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val peripheralSupported = runCatching {
            btManager?.adapter?.isMultipleAdvertisementSupported == true
        }.getOrDefault(false)

        return DeviceCapabilitySummary(
            apiLevel = VersionUtils.sdkInt,
            androidVersionLabel = VersionUtils.androidVersionLabel(),
            hasBleHardware = hasBle,
            hasBlePeripheralSupport = peripheralSupported,
            hasTelephonyHardware = hasTelephony,
            inCallServiceApiSupported = defaultDialerManager.isSupportedOnApi(),
            isDefaultDialer = defaultDialerManager.isDefaultDialer(),
            callControlCapability = callControlCapability,
            audioReport = audioCapabilityChecker.evaluate()
        )
    }
}

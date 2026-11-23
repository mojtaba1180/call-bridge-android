package com.example.callbridge.utils

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.callbridge.audio.AudioCapabilityChecker
import com.example.callbridge.audio.AudioCapabilityReport
import com.example.callbridge.callhistory.CallHistoryMode
import com.example.callbridge.device.SimManager
import com.example.callbridge.security.KeyStoreManager
import com.example.callbridge.sms.SmsBridgeMode
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.DefaultDialerManager

data class DeviceCapabilitySummary(
    val apiLevel: Int,
    val androidVersionLabel: String,
    val hasBleHardware: Boolean,
    val hasBlePeripheralSupport: Boolean,
    val hasTelephonyHardware: Boolean,
    val activeSimCount: Int,
    val contactsReadGranted: Boolean,
    val contactsWriteGranted: Boolean,
    val smsBridgeMode: SmsBridgeMode,
    val callHistoryMode: CallHistoryMode,
    val inCallServiceApiSupported: Boolean,
    val isDefaultDialer: Boolean,
    val callControlCapability: CallControlCapability,
    val tlsCertReady: Boolean,
    val audioReport: AudioCapabilityReport
)

/**
 * Aggregates hardware, OS API, Telecom, SIM, Contacts, Play Creator SMS/CallHistory modes,
 * BLE Peripheral, TLS certificate, and Audio capability probes.
 */
class DeviceCapabilities(
    context: Context,
    private val defaultDialerManager: DefaultDialerManager,
    private val audioCapabilityChecker: AudioCapabilityChecker,
    private val simManager: SimManager? = null,
    private val keyStoreManager: KeyStoreManager? = null
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

        val contactsRead = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        val contactsWrite = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val simCount = simManager?.subscriptions?.value?.size ?: 0
        val tlsReady = runCatching { keyStoreManager?.getServerCertificateFingerprint()?.isNotBlank() == true }
            .getOrDefault(false)

        return DeviceCapabilitySummary(
            apiLevel = VersionUtils.sdkInt,
            androidVersionLabel = VersionUtils.androidVersionLabel(),
            hasBleHardware = hasBle,
            hasBlePeripheralSupport = peripheralSupported,
            hasTelephonyHardware = hasTelephony,
            activeSimCount = simCount,
            contactsReadGranted = contactsRead,
            contactsWriteGranted = contactsWrite,
            smsBridgeMode = SmsBridgeMode.CREATOR_COMPLIANT_HANDOFF,
            callHistoryMode = CallHistoryMode.BRIDGELINK_OBSERVED_ONLY,
            inCallServiceApiSupported = defaultDialerManager.isSupportedOnApi(),
            isDefaultDialer = defaultDialerManager.isDefaultDialer(),
            callControlCapability = callControlCapability,
            tlsCertReady = tlsReady,
            audioReport = audioCapabilityChecker.evaluate()
        )
    }
}

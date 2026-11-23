package com.example.callbridge

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.callbridge.audio.AudioCapabilityChecker
import com.example.callbridge.audio.AudioCapabilityState
import com.example.callbridge.bluetooth.BlePermissions
import com.example.callbridge.service.BridgeForegroundService
import com.example.callbridge.telephony.CallDirection
import com.example.callbridge.telephony.CallInfo
import com.example.callbridge.telephony.CallManager
import com.example.callbridge.telephony.CallState
import com.example.callbridge.utils.VersionUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelephonyAndCapabilitiesTest {

    @After
    fun resetSdkProvider() {
        VersionUtils.sdkIntProvider = { Build.VERSION.SDK_INT }
    }

    @Test
    fun callManager_transitionsAcross9StateLifecycleCorrectly() {
        val idle = CallInfo.idle(1000L)
        val ringing = CallManager.computeNextCallInfo(idle, CallState.RINGING, "+15551234567", 2000L)
        assertEquals(CallState.RINGING, ringing.state)
        assertEquals(CallDirection.INCOMING, ringing.direction)
        assertNotNull(ringing.callId)

        val active = CallManager.computeNextCallInfo(ringing, CallState.ACTIVE, null, 3000L)
        assertEquals(CallState.ACTIVE, active.state)
        assertEquals(CallDirection.INCOMING, active.direction)
        assertEquals(ringing.callId, active.callId)
        assertEquals("+15551234567", active.phoneNumber)

        val holding = CallManager.computeNextCallInfo(active, CallState.HOLDING, null, 4000L)
        assertEquals(CallState.HOLDING, holding.state)
        assertEquals(CallDirection.INCOMING, holding.direction)

        val disconnected = CallManager.computeNextCallInfo(holding, CallState.IDLE, null, 5000L)
        assertEquals(CallState.DISCONNECTED, disconnected.state)
        assertEquals(ringing.callId, disconnected.callId)
    }

    @Test
    fun versionBranching_blePermissionsAndFgsTypesAdaptAcrossApi21To34() {
        // API 21: legacy Bluetooth, no runtime BLE permissions required
        VersionUtils.sdkIntProvider = { 21 }
        assertEquals(0, BlePermissions.requiredPeripheralPermissions().size)
        assertEquals(
            0,
            BridgeForegroundService.computeEligibleForegroundServiceTypes(
                canUseConnectedDevice = true,
                canUseDataSync = true,
                canUseMicrophone = true,
                canUsePhoneCall = true
            )
        )

        // API 31+: BLUETOOTH_ADVERTISE & BLUETOOTH_CONNECT only (no SCAN/Location)
        VersionUtils.sdkIntProvider = { 31 }
        val api31Perms = BlePermissions.requiredPeripheralPermissions().toSet()
        assertTrue(api31Perms.contains(android.Manifest.permission.BLUETOOTH_ADVERTISE))
        assertTrue(api31Perms.contains(android.Manifest.permission.BLUETOOTH_CONNECT))
        assertEquals(2, api31Perms.size)

        // API 34: conditional FGS bitmask includes only granted/active types
        VersionUtils.sdkIntProvider = { 34 }
        val mask = BridgeForegroundService.computeEligibleForegroundServiceTypes(
            canUseConnectedDevice = true,
            canUseDataSync = true,
            canUseMicrophone = false,
            canUsePhoneCall = false
        )
        assertTrue((mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0)
        assertTrue((mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0)
        assertEquals(0, mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        assertEquals(0, mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }

    @Test
    fun audioCapabilityChecker_honestlyReportsCellularAudioUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val checker = AudioCapabilityChecker(context)
        val report = checker.evaluate()

        assertEquals(AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE, report.cellularAudioCapability)
        assertTrue(report.explanation.isNotBlank())
    }
}

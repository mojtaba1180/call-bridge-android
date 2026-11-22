package com.example.callbridge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callbridge.audio.AudioBridgeMetrics
import com.example.callbridge.audio.AudioCapabilityReport
import com.example.callbridge.bluetooth.BleStatusSnapshot
import com.example.callbridge.core.App
import com.example.callbridge.core.BridgeLogEntry
import com.example.callbridge.core.Logger
import com.example.callbridge.network.NetworkStatusSnapshot
import com.example.callbridge.permissions.PermissionSnapshot
import com.example.callbridge.protocol.PairingStatusMessage
import com.example.callbridge.security.PairedDevice
import com.example.callbridge.security.PairingState
import com.example.callbridge.service.BridgeForegroundService
import com.example.callbridge.service.BridgeServiceState
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.CallInfo
import com.example.callbridge.telephony.CallOperationResult
import com.example.callbridge.utils.VersionUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val apiLabel: String,
    val serviceState: BridgeServiceState,
    val serviceLastError: String?,
    val permissions: PermissionSnapshot,
    val bleStatus: BleStatusSnapshot,
    val networkStatus: NetworkStatusSnapshot,
    val callInfo: CallInfo,
    val callCapability: CallControlCapability,
    val audioReport: AudioCapabilityReport,
    val audioMetrics: AudioBridgeMetrics,
    val pairingState: PairingState,
    val pairedDevice: PairedDevice?,
    val logs: List<BridgeLogEntry>
)

/**
 * Lifecycle-aware MVVM ViewModel combining all bridge subsystems into a single observable StateFlow.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private val corePart = combine(
        BridgeForegroundService.serviceState,
        BridgeForegroundService.lastError,
        container.permissionManager.snapshot,
        container.bleSignalingServer.status,
        container.localNetworkManager.status
    ) { svcState, svcErr, perms, ble, net ->
        CoreStateBundle(svcState, svcErr, perms, ble, net)
    }

    private val mediaAndCallPart = combine(
        container.callManager.callInfo,
        container.callManager.capability,
        container.localAudioBridge.capabilityReport,
        container.localAudioBridge.metrics,
        container.pairingManager.pairingState
    ) { call, cap, audioRep, audioMet, pairState ->
        MediaCallBundle(call, cap, audioRep, audioMet, pairState)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        corePart,
        mediaAndCallPart,
        container.pairingManager.pairedDevice,
        Logger.logs
    ) { core, mc, paired, logs ->
        DashboardUiState(
            apiLabel = VersionUtils.androidVersionLabel(),
            serviceState = core.serviceState,
            serviceLastError = core.serviceError,
            permissions = core.permissions,
            bleStatus = core.bleStatus,
            networkStatus = core.networkStatus,
            callInfo = mc.callInfo,
            callCapability = mc.callCapability,
            audioReport = mc.audioReport,
            audioMetrics = mc.audioMetrics,
            pairingState = mc.pairingState,
            pairedDevice = paired,
            logs = logs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DashboardUiState(
            apiLabel = VersionUtils.androidVersionLabel(),
            serviceState = BridgeForegroundService.serviceState.value,
            serviceLastError = BridgeForegroundService.lastError.value,
            permissions = container.permissionManager.snapshot.value,
            bleStatus = container.bleSignalingServer.status.value,
            networkStatus = container.localNetworkManager.status.value,
            callInfo = container.callManager.callInfo.value,
            callCapability = container.callManager.capability.value,
            audioReport = container.localAudioBridge.capabilityReport.value,
            audioMetrics = container.localAudioBridge.metrics.value,
            pairingState = container.pairingManager.pairingState.value,
            pairedDevice = container.pairingManager.pairedDevice.value,
            logs = Logger.logs.value
        )
    )

    fun refreshAllStatuses() {
        container.permissionManager.refresh()
        container.callManager.refreshCapability()
        container.bleSignalingServer.refreshStatus()
        container.localNetworkManager.refreshNetworkAndRebind()
        container.localAudioBridge.refreshCapabilities()
    }

    fun startBridge(): Boolean {
        val perms = container.permissionManager.refresh()
        if (!perms.canStartConnectedDeviceFgs() && !perms.canStartPhoneCallFgs()) {
            return false
        }
        return BridgeForegroundService.startService(
            context = getApplication(),
            includeMicrophone = container.localAudioBridge.metrics.value.isRunning
        )
    }

    fun stopBridge() {
        BridgeForegroundService.stopService(getApplication())
    }

    fun openPairingWindow(): String {
        return container.pairingManager.openPairingWindow()
    }

    fun approvePendingPairing() {
        val approved = container.pairingManager.approvePendingPairing()
        if (approved != null) {
            container.bleSignalingServer.notifyPairingStatus(
                PairingStatusMessage(
                    status = "APPROVED",
                    deviceId = approved.deviceId,
                    message = "Pairing approved on Android; send AUTH to establish session"
                )
            )
            _toastMessages.tryEmit("Paired with ${approved.deviceName}")
        }
    }

    fun rejectPendingPairing() {
        container.pairingManager.rejectPendingPairing()
        container.bleSignalingServer.notifyPairingStatus(
            PairingStatusMessage(
                status = "REJECTED",
                deviceId = null,
                message = "Pairing rejected by Android user"
            )
        )
    }

    fun unpairDevice() {
        container.pairingManager.unpair()
        container.sessionAuthenticator.invalidateSession("user unpaired")
        container.bleSignalingServer.refreshStatus()
    }

    fun runBleTest() {
        val summary = container.bleSignalingServer.runSelfTest()
        _toastMessages.tryEmit(summary)
    }

    fun runNetworkTest() {
        viewModelScope.launch {
            val summary = container.localNetworkManager.runSelfTest()
            _toastMessages.tryEmit(summary)
        }
    }

    fun toggleMicrophoneTest(): Boolean {
        val running = container.localAudioBridge.metrics.value.isRunning
        return if (running) {
            container.localAudioBridge.stop()
            BridgeForegroundService.updateForegroundTypes(getApplication(), includeMicrophone = false)
            true
        } else {
            BridgeForegroundService.updateForegroundTypes(getApplication(), includeMicrophone = true)
            val started = container.localAudioBridge.startMicrophoneLoopbackTest()
            if (!started) {
                _toastMessages.tryEmit("Microphone test could not start (check permission & audio hardware)")
            }
            started
        }
    }

    fun answerCall() = runCallOperation { container.callManager.answerCall() }
    fun rejectCall() = runCallOperation { container.callManager.rejectCall() }
    fun endCall() = runCallOperation { container.callManager.endCall() }
    fun dialNumber(number: String) = runCallOperation { container.callManager.dialNumber(number) }

    fun clearLogs() {
        Logger.clear()
    }

    private fun runCallOperation(block: suspend () -> CallOperationResult) {
        viewModelScope.launch {
            val result = block()
            _toastMessages.tryEmit(result.errorMessage)
        }
    }

    private data class CoreStateBundle(
        val serviceState: BridgeServiceState,
        val serviceError: String?,
        val permissions: PermissionSnapshot,
        val bleStatus: BleStatusSnapshot,
        val networkStatus: NetworkStatusSnapshot
    )

    private data class MediaCallBundle(
        val callInfo: CallInfo,
        val callCapability: CallControlCapability,
        val audioReport: AudioCapabilityReport,
        val audioMetrics: AudioBridgeMetrics,
        val pairingState: PairingState
    )
}

package com.example.callbridge.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.callbridge.audio.AudioBridgeMetrics
import com.example.callbridge.audio.AudioCapabilityReport
import com.example.callbridge.bluetooth.BleStatusSnapshot
import com.example.callbridge.contacts.BridgeContact
import com.example.callbridge.contacts.ContactPhone
import com.example.callbridge.contacts.ContactResult
import com.example.callbridge.contacts.ContactsStatusSnapshot
import com.example.callbridge.core.App
import com.example.callbridge.core.BridgeLogEntry
import com.example.callbridge.core.Logger
import com.example.callbridge.device.SimSubscriptionInfo
import com.example.callbridge.network.NetworkStatusSnapshot
import com.example.callbridge.permissions.PermissionSnapshot
import com.example.callbridge.protocol.PairingStatusMessage
import com.example.callbridge.security.PairedDevice
import com.example.callbridge.security.PairingQrGenerator
import com.example.callbridge.security.PairingQrPayload
import com.example.callbridge.security.PairingState
import com.example.callbridge.service.BridgeForegroundService
import com.example.callbridge.service.BridgeServiceState
import com.example.callbridge.sms.SmsSendResult
import com.example.callbridge.sms.SmsStatusSnapshot
import com.example.callbridge.sync.SyncStatusSnapshot
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.CallInfo
import com.example.callbridge.utils.BridgePhoneNumberUtils
import com.example.callbridge.utils.DeviceCapabilitySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Central ViewModel coordinating all 6 BridgeLink screens (Dashboard, Messages, Contacts,
 * Calls & Audio, Pairing, and Diagnostics) with real subsystem operations.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val container = (application as App).container

    val serviceState: StateFlow<BridgeServiceState> = BridgeForegroundService.serviceState
    val serviceError: StateFlow<String?> = BridgeForegroundService.lastError
    val permissions: StateFlow<PermissionSnapshot> = container.permissionManager.snapshot
    val bleStatus: StateFlow<BleStatusSnapshot> = container.bleSignalingServer.status
    val networkStatus: StateFlow<NetworkStatusSnapshot> = container.localNetworkManager.status
    val callInfo: StateFlow<CallInfo> = container.callManager.callInfo
    val callCapability: StateFlow<CallControlCapability> = container.callManager.capability
    val pairingState: StateFlow<PairingState> = container.pairingManager.pairingState
    val pairedDevice: StateFlow<PairedDevice?> = container.pairingManager.pairedDevice
    val audioCapability: StateFlow<AudioCapabilityReport> = container.localAudioBridge.capabilityReport
    val audioMetrics: StateFlow<AudioBridgeMetrics> = container.localAudioBridge.metrics
    val syncStatus: StateFlow<SyncStatusSnapshot> = container.syncEngine.status
    val contactsStatus: StateFlow<ContactsStatusSnapshot> = container.contactsRepository.status
    val smsStatus: StateFlow<SmsStatusSnapshot> = container.smsBridgeManager.status
    val simSubscriptions: StateFlow<List<SimSubscriptionInfo>> = container.simManager.subscriptions
    val selectedSimId: StateFlow<Int?> = container.simManager.selectedSubscriptionId
    val logs: StateFlow<List<BridgeLogEntry>> = Logger.logs

    val observedCallsCount: StateFlow<Int> = container.callHistoryRepository.observedCallCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _deviceCapabilities = MutableStateFlow(
        container.deviceCapabilities.inspect(container.callManager.capability.value)
    )
    val deviceCapabilities: StateFlow<DeviceCapabilitySummary> = _deviceCapabilities.asStateFlow()

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    private val _activeQrPayload = MutableStateFlow<PairingQrPayload?>(null)
    val activeQrPayload: StateFlow<PairingQrPayload?> = _activeQrPayload.asStateFlow()

    private val _lastDiagnosticResult = MutableStateFlow("Ready")
    val lastDiagnosticResult: StateFlow<String> = _lastDiagnosticResult.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    init {
        refreshAllStates()
        regeneratePairingQr()
    }

    fun refreshAllStates() {
        container.permissionManager.refresh()
        container.simManager.refresh()
        val cap = container.callManager.refreshCapability()
        container.bleSignalingServer.refreshStatus()
        container.localNetworkManager.refreshNetworkAndRebind()
        container.localAudioBridge.refreshCapabilities()
        container.contactsRepository.refreshStatus()
        viewModelScope.launch {
            container.smsBridgeManager.refreshStatus()
        }
        _deviceCapabilities.value = container.deviceCapabilities.inspect(cap)
    }

    fun startBridgeService() {
        refreshAllStates()
        val started = BridgeForegroundService.startService(
            context = getApplication(),
            includeMicrophone = false,
            includeDataSync = true
        )
        if (!started) {
            val err = BridgeForegroundService.lastError.value ?: "Could not start BridgeLink service"
            _toastEvents.tryEmit(err)
        }
    }

    fun stopBridgeService() {
        BridgeForegroundService.stopService(getApplication())
    }

    fun syncAndroidDataToIphone() {
        viewModelScope.launch {
            val snap = container.syncEngine.syncAndroidDataToIphone { pageJson ->
                container.localNetworkManager.sendRawFrame(pageJson)
            }
            val msg = if (snap.lastError == null) {
                "Sync completed: ${snap.contactsSynced} contacts, ${snap.callHistorySynced} observed calls, ${snap.smsOutboxSynced} SMS outbox"
            } else {
                "Sync failed: ${snap.lastError}"
            }
            _lastDiagnosticResult.value = msg
            _toastEvents.tryEmit(msg)
        }
    }

    fun regeneratePairingQr() {
        viewModelScope.launch(Dispatchers.Default) {
            val localIp = container.localNetworkManager.status.value.localIp
            val payload = container.pairingManager.openPairingWindow(currentLocalIp = localIp)
            val bitmap = PairingQrGenerator.renderQrBitmap(payload.toJsonString(), sizePx = 512)
            _activeQrPayload.value = payload
            _qrBitmap.value = bitmap
        }
    }

    fun approvePendingPairing() {
        val paired = container.pairingManager.approvePendingPairing()
        if (paired != null) {
            container.bleSignalingServer.notifyPairingStatus(
                PairingStatusMessage(
                    status = "APPROVED",
                    deviceId = paired.deviceId,
                    message = "Pairing approved by Android user. Proceed with AUTH."
                )
            )
            _toastEvents.tryEmit("Paired with ${paired.deviceName}")
        }
    }

    fun rejectPendingPairing() {
        container.pairingManager.rejectPendingPairing()
        container.bleSignalingServer.notifyPairingStatus(
            PairingStatusMessage(
                status = "REJECTED",
                deviceId = null,
                message = "Pairing rejected by Android user."
            )
        )
        _toastEvents.tryEmit("Pairing request rejected")
    }

    fun disconnectIphone() {
        container.bleSignalingServer.disconnectAllClients()
        container.localNetworkManager.disconnectClients()
        container.sessionAuthenticator.invalidateSession("user disconnected")
        _toastEvents.tryEmit("Disconnected active iPhone sessions")
    }

    fun revokePairing() {
        container.pairingManager.unpair()
        container.bleSignalingServer.disconnectAllClients()
        container.localNetworkManager.disconnectClients()
        container.sessionAuthenticator.invalidateSession("pairing revoked")
        regeneratePairingQr()
        _toastEvents.tryEmit("Pairing revoked and session invalidated")
    }

    fun selectSim(subscriptionId: Int) {
        container.simManager.selectSubscription(subscriptionId)
        viewModelScope.launch { container.smsBridgeManager.refreshStatus() }
    }

    fun queueSmsForSystemHandoff(
        destination: String,
        body: String,
        onReadyForHandoff: (SmsSendResult) -> Unit
    ) {
        viewModelScope.launch {
            val res = container.smsBridgeManager.enqueueSmsRequest(
                destination = destination,
                body = body,
                subscriptionId = container.simManager.selectedSubscriptionId.value,
                idempotencyKey = "ui_${UUID.randomUUID()}"
            )
            _toastEvents.tryEmit(res.message)
            if (res.requiresUserHandoff && res.outboxId.isNotEmpty()) {
                onReadyForHandoff(res)
            }
        }
    }

    fun markSmsDispatchedToSystem(outboxId: String) {
        viewModelScope.launch {
            container.smsBridgeManager.markDispatchedToSystem(outboxId)
        }
    }

    fun addAndroidContact(displayName: String, rawPhone: String) {
        viewModelScope.launch {
            val phones = if (rawPhone.isNotBlank()) {
                listOf(ContactPhone(rawPhone, BridgePhoneNumberUtils.normalizeForMatching(rawPhone), "MOBILE"))
            } else emptyList()
            val contact = BridgeContact(
                contactId = "",
                lookupKey = null,
                firstName = displayName.substringBefore(" ").takeIf { it.isNotBlank() },
                lastName = displayName.substringAfter(" ", "").takeIf { it.isNotBlank() },
                displayName = displayName,
                phones = phones,
                emails = emptyList(),
                organization = null,
                hasPhoto = false,
                updatedAtMs = System.currentTimeMillis()
            )
            val result = container.contactsRepository.createContact(contact)
            val msg = when (result) {
                is ContactResult.Success -> result.message
                is ContactResult.Conflict -> result.message
                is ContactResult.Failure -> "${result.errorCode}: ${result.message}"
            }
            _toastEvents.tryEmit(msg)
        }
    }

    fun answerCall() {
        viewModelScope.launch {
            val res = container.callManager.answerCall()
            _toastEvents.tryEmit(res.errorMessage)
        }
    }

    fun rejectCall() {
        viewModelScope.launch {
            val res = container.callManager.rejectCall()
            _toastEvents.tryEmit(res.errorMessage)
        }
    }

    fun hangupCall() {
        viewModelScope.launch {
            val res = container.callManager.endCall()
            _toastEvents.tryEmit(res.errorMessage)
        }
    }

    fun dialNumber(number: String) {
        viewModelScope.launch {
            val res = container.callManager.dialNumber(number, container.simManager.selectedSubscriptionId.value)
            _toastEvents.tryEmit(res.errorMessage)
        }
    }

    fun startLocalMicTest() {
        if (serviceState.value == BridgeServiceState.RUNNING) {
            BridgeForegroundService.updateForegroundTypes(getApplication(), includeMicrophone = true)
        }
        val started = container.localAudioBridge.startMicrophoneLoopbackTest()
        if (!started) {
            val err = container.localAudioBridge.metrics.value.lastError ?: "Microphone test failed"
            _toastEvents.tryEmit(err)
        } else {
            _toastEvents.tryEmit("Local 16 kHz PCM Mono mic test started (not cellular audio)")
        }
    }

    fun stopLocalMicTest() {
        container.localAudioBridge.stop()
        if (serviceState.value == BridgeServiceState.RUNNING) {
            BridgeForegroundService.updateForegroundTypes(getApplication(), includeMicrophone = false)
        }
        _toastEvents.tryEmit("Local microphone test stopped")
    }

    fun runBleSelfTest() {
        val result = container.bleSignalingServer.runSelfTest()
        _lastDiagnosticResult.value = result
        _toastEvents.tryEmit(result)
    }

    fun runWssSelfTest() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.localNetworkManager.runSelfTest()
            }
            _lastDiagnosticResult.value = result
            _toastEvents.tryEmit(result)
        }
    }

    fun runMicSelfTest() {
        val report = container.localAudioBridge.refreshCapabilities()
        val msg = "Cellular=${report.cellularAudioCapability.name} | Mic=${report.microphoneTestCapability.name} | 16kHz PCM Mono"
        _lastDiagnosticResult.value = msg
        _toastEvents.tryEmit(msg)
    }

    fun clearLogs() {
        Logger.clear()
    }
}

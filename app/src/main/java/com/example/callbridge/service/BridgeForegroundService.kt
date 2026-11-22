package com.example.callbridge.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.callbridge.audio.AudioCapabilityState
import com.example.callbridge.core.App
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.AppContainer
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandResultMessage
import com.example.callbridge.protocol.ProtocolConstants
import com.example.callbridge.telephony.CallControlCapability
import com.example.callbridge.telephony.CallInfo
import com.example.callbridge.telephony.CallState
import com.example.callbridge.utils.VersionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class BridgeServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Persistent Foreground Service orchestrating BLE GATT Peripheral signaling, Telephony call-state
 * monitoring, local Wi-Fi/Hotspot control server, and conditional Android 14 foreground service types.
 *
 * Foreground service types are enabled ONLY when their corresponding Android 14 prerequisites are met:
 * - connectedDevice: when Bluetooth peripheral permissions are granted
 * - microphone: ONLY when user explicitly triggers microphone test from a foreground UI flow & RECORD_AUDIO granted
 * - phoneCall: ONLY when the app holds ROLE_DIALER (default dialer)
 */
class BridgeForegroundService : Service() {

    private lateinit var container: AppContainer
    private lateinit var notificationManager: BridgeNotificationManager
    private lateinit var wakeLockManager: WakeLockManager

    private var serviceScope: CoroutineScope? = null
    private var isStarted = false
    private var activeFgsTypeMask = 0

    override fun onCreate() {
        super.onCreate()
        container = (application as App).container
        notificationManager = BridgeNotificationManager(this)
        wakeLockManager = WakeLockManager(this)
        notificationManager.ensureChannelCreated()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBridgeInternal()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_FGS_TYPES -> {
                val includeMic = intent.getBooleanExtra(EXTRA_INCLUDE_MIC_TYPE, false)
                if (isStarted) {
                    promoteForegroundWithEligibleTypes(includeMicrophone = includeMic)
                }
                return START_STICKY
            }
            else -> {
                val includeMic = intent?.getBooleanExtra(EXTRA_INCLUDE_MIC_TYPE, false) ?: false
                if (!isStarted) {
                    startBridgeInternal(includeMicrophone = includeMic)
                } else {
                    promoteForegroundWithEligibleTypes(includeMicrophone = includeMic)
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        tearDownBridge()
        super.onDestroy()
    }

    private fun startBridgeInternal(includeMicrophone: Boolean) {
        _serviceState.value = BridgeServiceState.STARTING
        _lastError.value = null

        val promoted = promoteForegroundWithEligibleTypes(includeMicrophone = includeMicrophone)
        if (!promoted) {
            _serviceState.value = BridgeServiceState.ERROR
            stopSelf()
            return
        }

        isStarted = true
        _serviceState.value = BridgeServiceState.RUNNING
        Logger.i("Service", "BridgeForegroundService started (fgsMask=$activeFgsTypeMask)")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serviceScope = scope

        container.bleSignalingServer.setCommandHandler(::handleBridgeCommand)
        container.localNetworkManager.setCommandHandler(::handleBridgeCommand)

        container.callManager.startMonitoring()
        container.bleSignalingServer.start()
        container.localNetworkManager.start()

        val audioReport = container.localAudioBridge.refreshCapabilities()
        if (audioReport.cellularAudioCapability == AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE) {
            Logger.w("Audio", "Audio capability unavailable for direct cellular calls (microphone test only)")
        }

        observeBridgeState(scope)
    }

    private fun promoteForegroundWithEligibleTypes(includeMicrophone: Boolean): Boolean {
        val permSnapshot = container.permissionManager.refresh()
        val fgsMask = computeEligibleForegroundServiceTypes(
            canUseConnectedDevice = permSnapshot.canStartConnectedDeviceFgs(),
            canUseMicrophone = includeMicrophone && permSnapshot.canStartMicrophoneFgs(),
            canUsePhoneCall = permSnapshot.canStartPhoneCallFgs()
        )

        if (VersionUtils.isAtLeastUpsideDownCake() && fgsMask == 0) {
            val msg = "Android 14+ requires Bluetooth permission (or Default Dialer role) before starting the Foreground Service."
            _lastError.value = msg
            Logger.e("Service", msg)
            return false
        }

        val notification = notificationManager.buildNotification(
            isIphoneConnected = container.bleSignalingServer.status.value.connectedDeviceAddress != null,
            callInfo = container.callManager.callInfo.value,
            audioCapability = container.localAudioBridge.capabilityReport.value.cellularAudioCapability
        )

        return try {
            ServiceCompat.startForeground(
                this,
                BridgeNotificationManager.NOTIFICATION_ID,
                notification,
                fgsMask
            )
            activeFgsTypeMask = fgsMask
            true
        } catch (se: SecurityException) {
            _lastError.value = "Foreground service denied by OS permissions: ${se.message}"
            Logger.e("Service", "SecurityException in startForeground", se)
            false
        } catch (t: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                t is ForegroundServiceStartNotAllowedException
            ) {
                _lastError.value = "Android 12+ blocked foreground service start from background."
                Logger.e("Service", "ForegroundServiceStartNotAllowedException", t)
            } else {
                _lastError.value = "Failed to enter foreground: ${t.message}"
                Logger.e("Service", "Failed to startForeground", t)
            }
            false
        }
    }

    private fun observeBridgeState(scope: CoroutineScope) {
        scope.launch {
            combine(
                container.callManager.callInfo,
                container.bleSignalingServer.status,
                container.localNetworkManager.status,
                container.localAudioBridge.capabilityReport
            ) { callInfo, bleStatus, _, audioReport ->
                Triple(callInfo, bleStatus.connectedDeviceAddress != null, audioReport.cellularAudioCapability)
            }.collect { (callInfo, isConnected, cellularCap) ->
                manageWakeLockForState(callInfo, isConnected)
                notificationManager.updateNotification(isConnected, callInfo, cellularCap)
                broadcastCurrentCallState(callInfo, cellularCap)
            }
        }
    }

    private fun manageWakeLockForState(callInfo: CallInfo, isConnected: Boolean) {
        val isCallActive = callInfo.state == CallState.RINGING || callInfo.state == CallState.OFFHOOK
        if (isCallActive) {
            wakeLockManager.acquireFor("active_call", AppConfig.WAKELOCK_MAX_TIMEOUT_MS)
        } else {
            wakeLockManager.releaseFor("active_call")
        }

        if (isConnected) {
            wakeLockManager.acquireFor("ble_session", AppConfig.WAKELOCK_TEMP_OPERATION_MS)
        } else {
            wakeLockManager.releaseFor("ble_session")
        }
    }

    private fun broadcastCurrentCallState(callInfo: CallInfo, audioCap: AudioCapabilityState) {
        val callControlSupported =
            container.callManager.capability.value == CallControlCapability.FULL_INCALL_SERVICE
        val message = CallStateMessage(
            state = callInfo.state.name,
            callId = callInfo.callId,
            number = callInfo.phoneNumber,
            displayName = callInfo.displayName,
            direction = callInfo.direction?.name,
            endpoint = container.localNetworkManager.currentEndpointPayload(),
            audioCapability = audioCap.name,
            callControlSupported = callControlSupported,
            timestamp = callInfo.timestamp
        )
        container.bleSignalingServer.publishCallState(message)
        container.localNetworkManager.publishCallState(message)
    }

    private suspend fun handleBridgeCommand(command: CommandMessage, source: String): CommandResultMessage {
        wakeLockManager.acquireFor("cmd_${command.id}", AppConfig.WAKELOCK_TEMP_OPERATION_MS)
        return try {
            when (command.cmd) {
                ProtocolConstants.CMD_ACCEPT -> {
                    val res = container.callManager.answerCall()
                    CommandResultMessage(
                        id = command.id,
                        success = res.success,
                        message = res.errorMessage,
                        errorCode = res.errorCode,
                        capabilityStatus = res.capabilityStatus.name
                    )
                }
                ProtocolConstants.CMD_REJECT -> {
                    val res = container.callManager.rejectCall()
                    CommandResultMessage(
                        id = command.id,
                        success = res.success,
                        message = res.errorMessage,
                        errorCode = res.errorCode,
                        capabilityStatus = res.capabilityStatus.name
                    )
                }
                ProtocolConstants.CMD_HANGUP -> {
                    val res = container.callManager.endCall()
                    CommandResultMessage(
                        id = command.id,
                        success = res.success,
                        message = res.errorMessage,
                        errorCode = res.errorCode,
                        capabilityStatus = res.capabilityStatus.name
                    )
                }
                ProtocolConstants.CMD_DIAL -> {
                    val res = container.callManager.dialNumber(command.number.orEmpty())
                    CommandResultMessage(
                        id = command.id,
                        success = res.success,
                        message = res.errorMessage,
                        errorCode = res.errorCode,
                        capabilityStatus = res.capabilityStatus.name
                    )
                }
                ProtocolConstants.CMD_GET_STATUS -> {
                    broadcastCurrentCallState(
                        container.callManager.callInfo.value,
                        container.localAudioBridge.capabilityReport.value.cellularAudioCapability
                    )
                    CommandResultMessage(
                        id = command.id,
                        success = true,
                        message = "Status snapshot published",
                        capabilityStatus = container.callManager.capability.value.name
                    )
                }
                else -> CommandResultMessage(
                    id = command.id,
                    success = false,
                    message = "Unhandled command ${command.cmd} from $source",
                    errorCode = "UNHANDLED_COMMAND"
                )
            }
        } finally {
            wakeLockManager.releaseFor("cmd_${command.id}")
        }
    }

    private fun stopBridgeInternal() {
        tearDownBridge()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tearDownBridge() {
        if (!isStarted && serviceScope == null) return
        isStarted = false
        serviceScope?.cancel()
        serviceScope = null

        container.bleSignalingServer.stop()
        container.localNetworkManager.stop()
        container.callManager.stopMonitoring()
        container.localAudioBridge.stop()
        wakeLockManager.releaseAll()

        _serviceState.value = BridgeServiceState.STOPPED
        Logger.i("Service", "BridgeForegroundService stopped and cleaned up")
    }

    companion object {
        const val ACTION_START = "com.example.callbridge.action.START"
        const val ACTION_STOP = "com.example.callbridge.action.STOP"
        const val ACTION_REFRESH_FGS_TYPES = "com.example.callbridge.action.REFRESH_FGS_TYPES"
        const val EXTRA_INCLUDE_MIC_TYPE = "extra_include_mic_type"

        private val _serviceState = MutableStateFlow(BridgeServiceState.STOPPED)
        val serviceState: StateFlow<BridgeServiceState> = _serviceState.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun startService(context: Context, includeMicrophone: Boolean = false): Boolean {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCLUDE_MIC_TYPE, includeMicrophone)
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    t is ForegroundServiceStartNotAllowedException
                ) {
                    _lastError.value = "Cannot start foreground service from background on Android 12+."
                    Logger.e("Service", "Blocked by ForegroundServiceStartNotAllowedException", t)
                } else {
                    _lastError.value = t.message
                    Logger.e("Service", "Failed to launch BridgeForegroundService", t)
                }
                false
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { runCatching { context.stopService(intent) } }
        }

        fun updateForegroundTypes(context: Context, includeMicrophone: Boolean) {
            if (_serviceState.value != BridgeServiceState.RUNNING) return
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_REFRESH_FGS_TYPES
                putExtra(EXTRA_INCLUDE_MIC_TYPE, includeMicrophone)
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Pure helper computing the exact Android 14+ foreground service type bitmask based on
         * actually granted runtime permissions and platform roles.
         */
        fun computeEligibleForegroundServiceTypes(
            canUseConnectedDevice: Boolean,
            canUseMicrophone: Boolean,
            canUsePhoneCall: Boolean
        ): Int {
            if (!VersionUtils.isAtLeastQ()) return 0
            var mask = 0
            if (canUseConnectedDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (canUseMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (canUsePhoneCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            return mask
        }
    }
}

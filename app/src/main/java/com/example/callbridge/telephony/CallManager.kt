package com.example.callbridge.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.callbridge.callhistory.CallHistoryRepository
import com.example.callbridge.callhistory.ObservedCallType
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.MessageSerializer
import com.example.callbridge.utils.VersionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates cellular call state monitoring, supported Telecom call control operations,
 * and automatic Play Creator-compliant recording of observed calls into [CallHistoryRepository].
 */
class CallManager(
    context: Context,
    private val defaultDialerManager: DefaultDialerManager,
    private val callHistoryRepository: CallHistoryRepository? = null
) {
    private val appContext = context.applicationContext

    private val _callInfo = MutableStateFlow(CallInfo.idle())
    val callInfo: StateFlow<CallInfo> = _callInfo.asStateFlow()

    private val _capability = MutableStateFlow(evaluateCapability())
    val capability: StateFlow<CallControlCapability> = _capability.asStateFlow()

    private var scope: CoroutineScope? = null
    private var inCallCollectorJob: Job? = null
    private var stateListener: CallStateListener? = null
    private var isMonitoring = false
    private var lastRecordedCallId: String? = null
    private var sawActiveForCurrentCall = false

    fun startMonitoring() {
        _capability.value = evaluateCapability()
        if (isMonitoring) return

        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = managerScope

        val listener = CallStateListener(appContext) { newState, bestEffortNumber ->
            applyTelephonyTransition(newState, bestEffortNumber)
        }
        stateListener = listener
        listener.start()

        inCallCollectorJob = managerScope.launch {
            InCallServiceBridge.activeCallInfo.collectLatest { inCall ->
                if (inCall != null) {
                    val prev = _callInfo.value
                    if (inCall.state == CallState.ACTIVE || inCall.state == CallState.HOLDING) {
                        sawActiveForCurrentCall = true
                    }
                    _callInfo.value = inCall
                    logCallTransition(inCall)
                    if (inCall.state == CallState.DISCONNECTED) {
                        persistCompletedCallIfNeeded(prev, inCall)
                    }
                }
            }
        }
        isMonitoring = true
    }

    fun stopMonitoring() {
        stateListener?.stop()
        stateListener = null
        inCallCollectorJob?.cancel()
        inCallCollectorJob = null
        scope?.cancel()
        scope = null
        isMonitoring = false
    }

    fun refreshCapability(): CallControlCapability {
        val current = evaluateCapability()
        _capability.value = current
        return current
    }

    fun evaluateCapability(): CallControlCapability {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return when {
            !hasPhoneState -> CallControlCapability.PERMISSION_MISSING
            !VersionUtils.isAtLeastM() -> CallControlCapability.MONITOR_ONLY_LEGACY_API
            defaultDialerManager.isDefaultDialer() -> CallControlCapability.FULL_INCALL_SERVICE
            else -> CallControlCapability.MONITOR_ONLY_NOT_DIALER
        }
    }

    /**
     * Pure deterministic transition engine shared between TelephonyManager callbacks and unit tests.
     */
    internal fun applyTelephonyTransition(
        newState: CallState,
        bestEffortNumber: String?,
        nowMs: Long = System.currentTimeMillis()
    ): CallInfo {
        val previous = _callInfo.value

        if (InCallServiceBridge.isServiceBound.value && newState != CallState.IDLE) {
            return previous
        }

        val updated = computeNextCallInfo(previous, newState, bestEffortNumber, nowMs)
        if (updated.state == CallState.ACTIVE || updated.state == CallState.HOLDING) {
            sawActiveForCurrentCall = true
        }
        _callInfo.value = updated
        logCallTransition(updated)

        if (updated.state == CallState.DISCONNECTED) {
            persistCompletedCallIfNeeded(previous, updated)
        }
        return updated
    }

    suspend fun answerCall(): CallOperationResult = withContext(Dispatchers.Main.immediate) {
        val cap = refreshCapability()
        if (!VersionUtils.isAtLeastM()) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "API_UNSUPPORTED",
                errorMessage = "Call answering via InCallService requires Android 6.0 (API 23+).",
                capabilityStatus = cap
            )
        }
        if (cap != CallControlCapability.FULL_INCALL_SERVICE) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "DEFAULT_DIALER_REQUIRED",
                errorMessage = "Set BridgeLink as the default dialer to answer calls via InCallService.",
                capabilityStatus = cap
            )
        }
        val (ok, msg) = InCallServiceBridge.answerRingingCall()
        if (ok) Logger.i("Telephony", "Incoming call answered")
        else Logger.w("Telephony", "Answer call failed: $msg")

        CallOperationResult(
            success = ok,
            errorCode = if (ok) null else "ANSWER_FAILED",
            errorMessage = msg,
            capabilityStatus = cap
        )
    }

    suspend fun rejectCall(): CallOperationResult = withContext(Dispatchers.Main.immediate) {
        val cap = refreshCapability()
        if (!VersionUtils.isAtLeastM()) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "API_UNSUPPORTED",
                errorMessage = "Call rejection via InCallService requires Android 6.0 (API 23+).",
                capabilityStatus = cap
            )
        }
        if (cap != CallControlCapability.FULL_INCALL_SERVICE) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "DEFAULT_DIALER_REQUIRED",
                errorMessage = "Set BridgeLink as the default dialer to reject calls via InCallService.",
                capabilityStatus = cap
            )
        }
        val (ok, msg) = InCallServiceBridge.rejectRingingCall()
        if (ok) Logger.i("Telephony", "Incoming call rejected")
        else Logger.w("Telephony", "Reject call failed: $msg")

        CallOperationResult(
            success = ok,
            errorCode = if (ok) null else "REJECT_FAILED",
            errorMessage = msg,
            capabilityStatus = cap
        )
    }

    suspend fun endCall(): CallOperationResult = withContext(Dispatchers.Main.immediate) {
        val cap = refreshCapability()
        if (!VersionUtils.isAtLeastM()) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "API_UNSUPPORTED",
                errorMessage = "Ending calls via InCallService requires Android 6.0 (API 23+).",
                capabilityStatus = cap
            )
        }
        if (cap != CallControlCapability.FULL_INCALL_SERVICE) {
            return@withContext CallOperationResult(
                success = false,
                errorCode = "DEFAULT_DIALER_REQUIRED",
                errorMessage = "Set BridgeLink as the default dialer to end calls via InCallService.",
                capabilityStatus = cap
            )
        }
        val (ok, msg) = InCallServiceBridge.disconnectActiveCall()
        if (ok) Logger.i("Telephony", "Active call disconnected")
        else Logger.w("Telephony", "Disconnect call failed: $msg")

        CallOperationResult(
            success = ok,
            errorCode = if (ok) null else "DISCONNECT_FAILED",
            errorMessage = msg,
            capabilityStatus = cap
        )
    }

    suspend fun dialNumber(number: String, subscriptionId: Int? = null): CallOperationResult =
        withContext(Dispatchers.Main.immediate) {
            val cap = refreshCapability()
            val sanitized = number.trim()
            if (!MessageSerializer.isValidDialNumber(sanitized)) {
                return@withContext CallOperationResult(
                    success = false,
                    errorCode = "INVALID_PHONE_NUMBER",
                    errorMessage = "Invalid phone number format.",
                    capabilityStatus = cap
                )
            }

            val hasCallPhone = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCallPhone) {
                return@withContext CallOperationResult(
                    success = false,
                    errorCode = "CALL_PHONE_PERMISSION_MISSING",
                    errorMessage = "CALL_PHONE permission is required to place outgoing calls.",
                    capabilityStatus = cap
                )
            }

            val uri = Uri.fromParts("tel", sanitized, null)
            return@withContext try {
                if (VersionUtils.isAtLeastM() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null) {
                        val extras = Bundle().apply {
                            if (subscriptionId != null) {
                                putInt("android.telephony.extra.SUBSCRIPTION_INDEX", subscriptionId)
                            }
                        }
                        telecomManager.placeCall(uri, extras)
                        Logger.i("Telephony", "Outgoing call requested via TelecomManager to ${Logger.maskPhoneNumber(sanitized)}")
                        CallOperationResult(
                            success = true,
                            errorCode = null,
                            errorMessage = "Outgoing call placed via TelecomManager",
                            capabilityStatus = cap
                        )
                    } else {
                        CallOperationResult(
                            success = false,
                            errorCode = "TELECOM_UNAVAILABLE",
                            errorMessage = "TelecomManager is unavailable on this device.",
                            capabilityStatus = cap
                        )
                    }
                } else {
                    val intent = Intent(Intent.ACTION_CALL, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    Logger.i("Telephony", "Outgoing call started (API 21-22) to ${Logger.maskPhoneNumber(sanitized)}")
                    CallOperationResult(
                        success = true,
                        errorCode = null,
                        errorMessage = "Outgoing call initiated",
                        capabilityStatus = cap
                    )
                }
            } catch (se: SecurityException) {
                Logger.e("Telephony", "SecurityException placing outgoing call", se)
                CallOperationResult(
                    success = false,
                    errorCode = "SECURITY_EXCEPTION",
                    errorMessage = "System denied call placement: ${se.message}",
                    capabilityStatus = cap
                )
            } catch (t: Throwable) {
                Logger.e("Telephony", "Error placing outgoing call", t)
                CallOperationResult(
                    success = false,
                    errorCode = "DIAL_FAILED",
                    errorMessage = "Failed to initiate outgoing call: ${t.message}",
                    capabilityStatus = cap
                )
            }
        }

    private fun persistCompletedCallIfNeeded(previous: CallInfo, finished: CallInfo) {
        val repo = callHistoryRepository ?: return
        val callId = finished.callId ?: previous.callId ?: "call_${UUID.randomUUID().toString().take(8)}"
        if (callId == lastRecordedCallId) return
        lastRecordedCallId = callId

        val startMs = finished.startTime ?: previous.startTime ?: previous.timestamp
        val durationSec = if (sawActiveForCurrentCall) {
            ((finished.timestamp - startMs) / 1000L).coerceAtLeast(0L)
        } else 0L

        val callType = when (finished.direction ?: previous.direction) {
            CallDirection.INCOMING -> if (sawActiveForCurrentCall) ObservedCallType.INCOMING else ObservedCallType.MISSED
            CallDirection.OUTGOING -> ObservedCallType.OUTGOING
            else -> ObservedCallType.UNKNOWN
        }
        sawActiveForCurrentCall = false

        val recordScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recordScope.launch(Dispatchers.IO) {
            runCatching {
                repo.recordObservedCall(
                    callId = callId,
                    rawPhoneNumber = finished.phoneNumber ?: previous.phoneNumber,
                    displayName = finished.displayName ?: previous.displayName,
                    callType = callType,
                    startedAtMs = startMs,
                    durationSec = durationSec,
                    subscriptionId = finished.subscriptionId ?: previous.subscriptionId
                )
            }
        }
    }

    private fun logCallTransition(info: CallInfo) {
        val masked = Logger.maskPhoneNumber(info.phoneNumber)
        when (info.state) {
            CallState.RINGING -> Logger.i("Telephony", "Incoming call detected ($masked)")
            CallState.DIALING -> Logger.i("Telephony", "Dialing outgoing call ($masked)")
            CallState.CONNECTING -> Logger.i("Telephony", "Call connecting ($masked)")
            CallState.ACTIVE -> Logger.i("Telephony", "Call active (${info.direction ?: CallDirection.UNKNOWN})")
            CallState.HOLDING -> Logger.i("Telephony", "Call on hold")
            CallState.DISCONNECTING -> Logger.i("Telephony", "Call disconnecting")
            CallState.DISCONNECTED -> Logger.i("Telephony", "Call disconnected")
            CallState.IDLE -> Logger.i("Telephony", "Call state idle")
            CallState.UNKNOWN -> Logger.w("Telephony", "Call state unknown")
        }
    }

    companion object {
        fun computeNextCallInfo(
            previous: CallInfo,
            newState: CallState,
            bestEffortNumber: String?,
            nowMs: Long = System.currentTimeMillis()
        ): CallInfo {
            return when (newState) {
                CallState.RINGING -> CallInfo(
                    callId = previous.callId ?: "call_${UUID.randomUUID().toString().take(8)}",
                    phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                    displayName = previous.displayName,
                    state = CallState.RINGING,
                    direction = CallDirection.INCOMING,
                    timestamp = nowMs,
                    startTime = previous.startTime ?: nowMs,
                    subscriptionId = previous.subscriptionId
                )

                CallState.DIALING, CallState.CONNECTING -> CallInfo(
                    callId = previous.callId ?: "call_${UUID.randomUUID().toString().take(8)}",
                    phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                    displayName = previous.displayName,
                    state = newState,
                    direction = previous.direction ?: CallDirection.OUTGOING,
                    timestamp = nowMs,
                    startTime = previous.startTime ?: nowMs,
                    subscriptionId = previous.subscriptionId
                )

                CallState.ACTIVE, CallState.HOLDING -> {
                    val direction = when {
                        previous.state == CallState.RINGING -> CallDirection.INCOMING
                        previous.direction != null && previous.state.isOngoing() -> previous.direction
                        else -> CallDirection.OUTGOING
                    }
                    CallInfo(
                        callId = previous.callId ?: "call_${UUID.randomUUID().toString().take(8)}",
                        phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                        displayName = previous.displayName,
                        state = newState,
                        direction = direction,
                        timestamp = nowMs,
                        startTime = previous.startTime ?: nowMs,
                        subscriptionId = previous.subscriptionId
                    )
                }

                CallState.DISCONNECTING -> CallInfo(
                    callId = previous.callId,
                    phoneNumber = previous.phoneNumber,
                    displayName = previous.displayName,
                    state = CallState.DISCONNECTING,
                    direction = previous.direction,
                    timestamp = nowMs,
                    startTime = previous.startTime,
                    subscriptionId = previous.subscriptionId
                )

                CallState.IDLE -> {
                    if (previous.state.isOngoing() || previous.state == CallState.DISCONNECTING) {
                        CallInfo(
                            callId = previous.callId,
                            phoneNumber = previous.phoneNumber,
                            displayName = previous.displayName,
                            state = CallState.DISCONNECTED,
                            direction = previous.direction,
                            timestamp = nowMs,
                            startTime = previous.startTime,
                            subscriptionId = previous.subscriptionId
                        )
                    } else {
                        CallInfo.idle(nowMs)
                    }
                }

                CallState.DISCONNECTED -> CallInfo(
                    callId = previous.callId,
                    phoneNumber = previous.phoneNumber,
                    displayName = previous.displayName,
                    state = CallState.DISCONNECTED,
                    direction = previous.direction,
                    timestamp = nowMs,
                    startTime = previous.startTime,
                    subscriptionId = previous.subscriptionId
                )

                CallState.UNKNOWN -> CallInfo(
                    callId = previous.callId,
                    phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                    displayName = previous.displayName,
                    state = CallState.UNKNOWN,
                    direction = CallDirection.UNKNOWN,
                    timestamp = nowMs,
                    startTime = previous.startTime,
                    subscriptionId = previous.subscriptionId
                )
            }
        }
    }
}

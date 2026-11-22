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
 * Coordinates cellular call state monitoring and supported Telecom call control operations.
 *
 * Strictly honest about platform capabilities:
 * - Never fakes success when InCallService / ROLE_DIALER is missing.
 * - Never uses Accessibility Services, undocumented reflection, or hidden APIs.
 * - Combines TelephonyManager state listener with richer InCallService telemetry when available.
 */
class CallManager(
    context: Context,
    private val defaultDialerManager: DefaultDialerManager
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
                    _callInfo.value = inCall
                    logCallTransition(inCall)
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

        // If InCallService is bound and actively tracking this call, prefer its richer metadata
        // unless TelephonyManager reports IDLE to clear stale states.
        if (InCallServiceBridge.isServiceBound.value && newState != CallState.IDLE) {
            return previous
        }

        val updated = computeNextCallInfo(previous, newState, bestEffortNumber, nowMs)
        _callInfo.value = updated
        logCallTransition(updated)
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
                errorMessage = "Set CallBridge as the default dialer to answer calls via InCallService.",
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
                errorMessage = "Set CallBridge as the default dialer to reject calls via InCallService.",
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
                errorMessage = "Set CallBridge as the default dialer to end calls via InCallService.",
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

    /**
     * Initiates an outgoing cellular call using TelecomManager.placeCall (API 23+ with CALL_PHONE)
     * or returns a clear error/confirmation requirement without bypassing background activity limits.
     */
    suspend fun dialNumber(number: String): CallOperationResult = withContext(Dispatchers.Main.immediate) {
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
                    telecomManager.placeCall(uri, Bundle())
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
                // API 21-22 fallback using ACTION_CALL when CALL_PHONE permission is granted
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

    private fun logCallTransition(info: CallInfo) {
        val masked = Logger.maskPhoneNumber(info.phoneNumber)
        when (info.state) {
            CallState.RINGING -> Logger.i("Telephony", "Incoming call detected ($masked)")
            CallState.OFFHOOK -> Logger.i("Telephony", "Call active (${info.direction ?: CallDirection.UNKNOWN})")
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
                    timestamp = nowMs
                )

                CallState.OFFHOOK -> {
                    val direction = when {
                        previous.state == CallState.RINGING -> CallDirection.INCOMING
                        previous.direction != null && previous.state == CallState.OFFHOOK -> previous.direction
                        else -> CallDirection.OUTGOING
                    }
                    CallInfo(
                        callId = previous.callId ?: "call_${UUID.randomUUID().toString().take(8)}",
                        phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                        displayName = previous.displayName,
                        state = CallState.OFFHOOK,
                        direction = direction,
                        timestamp = nowMs
                    )
                }

                CallState.IDLE -> {
                    if (previous.state == CallState.RINGING || previous.state == CallState.OFFHOOK) {
                        CallInfo(
                            callId = previous.callId,
                            phoneNumber = previous.phoneNumber,
                            displayName = previous.displayName,
                            state = CallState.DISCONNECTED,
                            direction = previous.direction,
                            timestamp = nowMs
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
                    timestamp = nowMs
                )

                CallState.UNKNOWN -> CallInfo(
                    callId = previous.callId,
                    phoneNumber = bestEffortNumber ?: previous.phoneNumber,
                    displayName = previous.displayName,
                    state = CallState.UNKNOWN,
                    direction = CallDirection.UNKNOWN,
                    timestamp = nowMs
                )
            }
        }
    }
}

package com.example.callbridge.telephony

import android.annotation.TargetApi
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.example.callbridge.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Official Android Telecom InCallService (API 23+).
 *
 * Active ONLY when the user grants CallBridge the default dialer role (ROLE_DIALER on Android 10+
 * or default dialer on Android 6–9). Safely tracks live android.telecom.Call objects, cleans up
 * callbacks immediately on removal so no stale references are retained, and exposes answer, reject,
 * and disconnect actions.
 */
@TargetApi(Build.VERSION_CODES.M)
class BridgeInCallService : InCallService() {

    private val callCallbacks = ConcurrentHashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        InCallServiceBridge.onServiceBound(this)
        Logger.i("Telecom", "BridgeInCallService created")
    }

    override fun onDestroy() {
        clearAllCallbacks()
        InCallServiceBridge.onServiceUnbound()
        Logger.i("Telecom", "BridgeInCallService destroyed")
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callId = "call_${UUID.randomUUID().toString().take(8)}"
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                InCallServiceBridge.onCallUpdated(call, callId)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                InCallServiceBridge.onCallUpdated(call, callId)
            }
        }
        callCallbacks[call] = callback
        call.registerCallback(callback)
        InCallServiceBridge.onCallAdded(call, callId)
        Logger.i("Telecom", "InCallService call added (id=$callId)")
    }

    override fun onCallRemoved(call: Call) {
        callCallbacks.remove(call)?.let { cb ->
            runCatching { call.unregisterCallback(cb) }
        }
        InCallServiceBridge.onCallRemoved(call)
        Logger.i("Telecom", "InCallService call removed")
        super.onCallRemoved(call)
    }

    private fun clearAllCallbacks() {
        callCallbacks.forEach { (call, cb) ->
            runCatching { call.unregisterCallback(cb) }
        }
        callCallbacks.clear()
    }
}

/**
 * Thread-safe bridge between the OS-instantiated [BridgeInCallService] and [CallManager].
 * Prevents memory leaks by removing every [Call] reference immediately on removal or service unbind.
 */
object InCallServiceBridge {

    private val lock = Any()
    private var bound = false
    private val trackedCalls = LinkedHashMap<Call, String>()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    private val _activeCallInfo = MutableStateFlow<CallInfo?>(null)
    val activeCallInfo: StateFlow<CallInfo?> = _activeCallInfo.asStateFlow()

    fun onServiceBound(@Suppress("UNUSED_PARAMETER") service: BridgeInCallService) = synchronized(lock) {
        bound = true
        _isServiceBound.value = true
    }

    fun onServiceUnbound() = synchronized(lock) {
        bound = false
        trackedCalls.clear()
        _isServiceBound.value = false
        _activeCallInfo.value = null
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun onCallAdded(call: Call, callId: String) = synchronized(lock) {
        trackedCalls[call] = callId
        publishPrimaryCallLocked()
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun onCallUpdated(call: Call, callId: String) = synchronized(lock) {
        if (!trackedCalls.containsKey(call)) {
            trackedCalls[call] = callId
        }
        publishPrimaryCallLocked()
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun onCallRemoved(call: Call) = synchronized(lock) {
        val removedId = trackedCalls.remove(call)
        if (trackedCalls.isEmpty()) {
            _activeCallInfo.value = CallInfo(
                callId = removedId,
                phoneNumber = null,
                displayName = null,
                state = CallState.DISCONNECTED,
                direction = null,
                timestamp = System.currentTimeMillis()
            )
        } else {
            publishPrimaryCallLocked()
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun answerRingingCall(): Pair<Boolean, String> = synchronized(lock) {
        if (!bound) return false to "InCallService is not active (app is not default dialer)"
        val ringing = trackedCalls.keys.firstOrNull { currentCallState(it) == Call.STATE_RINGING }
            ?: return false to "No incoming ringing call found"
        return try {
            ringing.answer(VideoProfile.STATE_AUDIO_ONLY)
            true to "Call answered via InCallService"
        } catch (t: Throwable) {
            Logger.e("Telecom", "Failed to answer call", t)
            false to "Failed to answer call: ${t.message}"
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun rejectRingingCall(): Pair<Boolean, String> = synchronized(lock) {
        if (!bound) return false to "InCallService is not active (app is not default dialer)"
        val ringing = trackedCalls.keys.firstOrNull { currentCallState(it) == Call.STATE_RINGING }
            ?: return false to "No incoming ringing call found"
        return try {
            ringing.reject(false, null)
            true to "Incoming call rejected via InCallService"
        } catch (t: Throwable) {
            Logger.e("Telecom", "Failed to reject call", t)
            false to "Failed to reject call: ${t.message}"
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    fun disconnectActiveCall(): Pair<Boolean, String> = synchronized(lock) {
        if (!bound) return false to "InCallService is not active (app is not default dialer)"
        val call = trackedCalls.keys.lastOrNull()
            ?: return false to "No active call to disconnect"
        return try {
            call.disconnect()
            true to "Call disconnected via InCallService"
        } catch (t: Throwable) {
            Logger.e("Telecom", "Failed to disconnect call", t)
            false to "Failed to disconnect call: ${t.message}"
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun publishPrimaryCallLocked() {
        val entry = trackedCalls.entries.lastOrNull() ?: run {
            _activeCallInfo.value = null
            return
        }
        val call = entry.key
        val callId = entry.value
        val rawState = currentCallState(call)
        val mappedState = mapInCallState(rawState)
        val details = call.details
        val number = details?.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val displayName = details?.callerDisplayName?.takeIf { it.isNotBlank() }
        val direction = resolveCallDirection(details, rawState)

        _activeCallInfo.value = CallInfo(
            callId = callId,
            phoneNumber = number,
            displayName = displayName,
            state = mappedState,
            direction = direction,
            timestamp = System.currentTimeMillis()
        )
    }

    @Suppress("DEPRECATION")
    @TargetApi(Build.VERSION_CODES.M)
    private fun currentCallState(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun resolveCallDirection(details: Call.Details?, rawState: Int): CallDirection {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && details != null) {
            return when (details.callDirection) {
                Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
                Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
                else -> CallDirection.UNKNOWN
            }
        }
        return when (rawState) {
            Call.STATE_RINGING -> CallDirection.INCOMING
            Call.STATE_DIALING, Call.STATE_CONNECTING -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun mapInCallState(telecomState: Int): CallState = when (telecomState) {
        Call.STATE_RINGING -> CallState.RINGING
        Call.STATE_DIALING,
        Call.STATE_CONNECTING,
        Call.STATE_ACTIVE,
        Call.STATE_HOLDING -> CallState.OFFHOOK
        Call.STATE_DISCONNECTING,
        Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
        else -> CallState.UNKNOWN
    }
}

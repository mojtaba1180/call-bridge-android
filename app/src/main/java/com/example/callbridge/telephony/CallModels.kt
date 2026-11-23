package com.example.callbridge.telephony

/**
 * Rich BridgeLink call lifecycle states matching BRIDGELINK_PROTOCOL.md (9 states).
 */
enum class CallState {
    IDLE,
    RINGING,
    DIALING,
    CONNECTING,
    ACTIVE,
    HOLDING,
    DISCONNECTING,
    DISCONNECTED,
    UNKNOWN;

    fun isOngoing(): Boolean =
        this == RINGING || this == DIALING || this == CONNECTING || this == ACTIVE || this == HOLDING
}

enum class CallDirection {
    INCOMING,
    OUTGOING,
    UNKNOWN
}

data class CallInfo(
    val callId: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val state: CallState,
    val direction: CallDirection?,
    val timestamp: Long,
    val startTime: Long? = null,
    val subscriptionId: Int? = null
) {
    companion object {
        fun idle(nowMs: Long = System.currentTimeMillis()): CallInfo = CallInfo(
            callId = null,
            phoneNumber = null,
            displayName = null,
            state = CallState.IDLE,
            direction = null,
            timestamp = nowMs,
            startTime = null,
            subscriptionId = null
        )
    }
}

enum class CallControlCapability {
    FULL_INCALL_SERVICE,      // API 23+ & app holds default dialer / active InCallService
    MONITOR_ONLY_NOT_DIALER,  // API 23+ but app is NOT default dialer (answer/reject/hangup unavailable)
    MONITOR_ONLY_LEGACY_API,  // API 21-22 (InCallService does not exist publicly)
    PERMISSION_MISSING        // READ_PHONE_STATE not granted
}

data class CallOperationResult(
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String,
    val capabilityStatus: CallControlCapability
)

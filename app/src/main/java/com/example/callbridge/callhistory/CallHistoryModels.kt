package com.example.callbridge.callhistory

enum class CallHistoryMode {
    BRIDGELINK_OBSERVED_ONLY
}

enum class ObservedCallType {
    INCOMING,
    OUTGOING,
    MISSED,
    REJECTED,
    UNKNOWN
}

data class CallHistoryRecord(
    val callId: String,
    val phoneNumberMasked: String,
    val displayName: String?,
    val callType: ObservedCallType,
    val startedAtMs: Long,
    val durationSec: Long,
    val subscriptionId: Int?
)

data class CallHistoryStatusSnapshot(
    val mode: CallHistoryMode,
    val systemCallLogPermitted: Boolean,
    val observedCallCount: Int,
    val lastSyncedAtMs: Long?
)

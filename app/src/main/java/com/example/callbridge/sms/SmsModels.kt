package com.example.callbridge.sms

enum class SmsBridgeMode {
    CREATOR_COMPLIANT_HANDOFF
}

enum class SmsOutboxState {
    QUEUED,
    HANDOFF_REQUIRED,
    DISPATCHED_TO_SYSTEM,
    FAILED,
    DELIVERY_UNKNOWN
}

data class SmsSendResult(
    val outboxId: String,
    val idempotencyKey: String,
    val state: SmsOutboxState,
    val duplicateIgnored: Boolean,
    val requiresUserHandoff: Boolean,
    val message: String
)

data class SmsStatusSnapshot(
    val mode: SmsBridgeMode,
    val defaultSmsRoleHeld: Boolean,
    val backgroundSmsPermissionsAllowed: Boolean,
    val outboxTotalCount: Int,
    val activeSimCount: Int,
    val selectedSimLabel: String,
    val lastOutboxState: String?
)

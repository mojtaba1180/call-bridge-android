package com.example.callbridge.permissions

enum class PermissionCategory {
    BLUETOOTH,
    PHONE_STATE,
    NOTIFICATIONS,
    MICROPHONE,
    CALL_PHONE,
    CONTACTS,
    DEFAULT_DIALER_ROLE,
    BATTERY_OPTIMIZATION
}

enum class GrantState {
    GRANTED,
    DENIED,
    NOT_REQUIRED_ON_API,
    UNSUPPORTED_ON_API,
    RESTRICTED_PLAY_CREATOR_MODE
}

data class PermissionSnapshot(
    val bluetooth: GrantState,
    val phoneState: GrantState,
    val notifications: GrantState,
    val microphone: GrantState,
    val callPhone: GrantState,
    val contactsRead: GrantState,
    val contactsWrite: GrantState,
    val defaultDialerRole: GrantState,
    val batteryOptimizationIgnored: GrantState,
    // Explicitly reflects Google Play Creator Program compliance (no READ_SMS/SEND_SMS/RECEIVE_SMS/READ_CALL_LOG)
    val smsBackgroundAccess: GrantState = GrantState.RESTRICTED_PLAY_CREATOR_MODE,
    val callLogProviderAccess: GrantState = GrantState.RESTRICTED_PLAY_CREATOR_MODE
) {
    fun canStartConnectedDeviceFgs(): Boolean = bluetooth == GrantState.GRANTED
    fun canStartMicrophoneFgs(): Boolean = microphone == GrantState.GRANTED
    fun canStartPhoneCallFgs(): Boolean = defaultDialerRole == GrantState.GRANTED
    fun canReadContacts(): Boolean = contactsRead == GrantState.GRANTED
    fun canWriteContacts(): Boolean = contactsWrite == GrantState.GRANTED
}

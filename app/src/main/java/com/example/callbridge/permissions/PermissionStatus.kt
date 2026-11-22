package com.example.callbridge.permissions

enum class PermissionCategory {
    BLUETOOTH,
    PHONE_STATE,
    NOTIFICATIONS,
    MICROPHONE,
    CALL_PHONE,
    DEFAULT_DIALER_ROLE,
    BATTERY_OPTIMIZATION
}

enum class GrantState {
    GRANTED,
    DENIED,
    NOT_REQUIRED_ON_API,
    UNSUPPORTED_ON_API
}

data class PermissionSnapshot(
    val bluetooth: GrantState,
    val phoneState: GrantState,
    val notifications: GrantState,
    val microphone: GrantState,
    val callPhone: GrantState,
    val defaultDialerRole: GrantState,
    val batteryOptimizationIgnored: GrantState
) {
    fun canStartConnectedDeviceFgs(): Boolean = bluetooth == GrantState.GRANTED
    fun canStartMicrophoneFgs(): Boolean = microphone == GrantState.GRANTED
    fun canStartPhoneCallFgs(): Boolean = defaultDialerRole == GrantState.GRANTED
}

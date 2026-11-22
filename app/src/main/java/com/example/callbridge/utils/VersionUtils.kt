package com.example.callbridge.utils

import android.os.Build

/**
 * Centralized API-level compatibility predicates (API 21 through API 34+).
 * Allows deterministic overriding in unit tests via [sdkIntProvider].
 */
object VersionUtils {

    @Volatile
    var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }

    val sdkInt: Int
        get() = sdkIntProvider()

    /** Android 6.0 (API 23): Runtime permissions, InCallService, Battery optimization intent. */
    fun isAtLeastM(): Boolean = sdkInt >= Build.VERSION_CODES.M

    /** Android 8.0 (API 26): Notification channels, startForegroundService. */
    fun isAtLeastO(): Boolean = sdkInt >= Build.VERSION_CODES.O

    /** Android 9.0 (API 28): TelecomManager.endCall deprecation timeline & foreground service permission. */
    fun isAtLeastP(): Boolean = sdkInt >= Build.VERSION_CODES.P

    /** Android 10 (API 29): RoleManager (ROLE_DIALER), FGS types introduced. */
    fun isAtLeastQ(): Boolean = sdkInt >= Build.VERSION_CODES.Q

    /** Android 11 (API 30): Scoped FGS microphone/camera types. */
    fun isAtLeastR(): Boolean = sdkInt >= Build.VERSION_CODES.R

    /** Android 12 (API 31): BLUETOOTH_ADVERTISE/CONNECT, TelephonyCallback, FGS background launch restriction. */
    fun isAtLeastS(): Boolean = sdkInt >= Build.VERSION_CODES.S

    /** Android 13 (API 33): POST_NOTIFICATIONS runtime permission, new BluetoothGattServer notify API. */
    fun isAtLeastTiramisu(): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** Android 14 (API 34): Strict foregroundServiceType enforcement & permissions. */
    fun isAtLeastUpsideDownCake(): Boolean = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun androidVersionLabel(): String = "Android ${Build.VERSION.RELEASE} (API $sdkInt)"
}

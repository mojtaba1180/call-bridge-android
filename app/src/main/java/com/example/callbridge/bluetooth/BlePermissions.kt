package com.example.callbridge.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.callbridge.utils.VersionUtils

/**
 * API-aware BLE peripheral permission helper:
 * - Android 12+ (API 31+): BLUETOOTH_ADVERTISE + BLUETOOTH_CONNECT (no location permission needed for peripheral-only)
 * - Android 5–11 (API 21–30): install-time BLUETOOTH + BLUETOOTH_ADMIN (no runtime prompt needed)
 */
object BlePermissions {

    fun requiredPeripheralPermissions(): Array<String> {
        return if (VersionUtils.isAtLeastS()) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            emptyArray()
        }
    }

    fun hasAdvertisePermission(context: Context): Boolean {
        return if (VersionUtils.isAtLeastS()) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasConnectPermission(context: Context): Boolean {
        return if (VersionUtils.isAtLeastS()) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasPeripheralPermissions(context: Context): Boolean {
        return hasAdvertisePermission(context) && hasConnectPermission(context)
    }
}

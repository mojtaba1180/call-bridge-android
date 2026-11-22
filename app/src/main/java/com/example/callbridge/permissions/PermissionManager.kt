package com.example.callbridge.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.callbridge.bluetooth.BlePermissions
import com.example.callbridge.telephony.DefaultDialerManager
import com.example.callbridge.utils.VersionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central API-aware permission and role evaluator.
 * Never requests permissions automatically at launch; exposes exact runtime permission arrays
 * so UI can request only the permissions required by the feature the user activates.
 */
class PermissionManager(
    private val context: Context,
    private val defaultDialerManager: DefaultDialerManager
) {
    private val appContext = context.applicationContext

    private val _snapshot = MutableStateFlow(evaluate())
    val snapshot: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    fun refresh(): PermissionSnapshot {
        val latest = evaluate()
        _snapshot.value = latest
        return latest
    }

    fun evaluate(): PermissionSnapshot {
        val btState = if (BlePermissions.hasPeripheralPermissions(appContext)) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }

        val phoneState = if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }

        val notifState = when {
            !VersionUtils.isAtLeastTiramisu() -> GrantState.NOT_REQUIRED_ON_API
            hasPermission(Manifest.permission.POST_NOTIFICATIONS) -> GrantState.GRANTED
            else -> GrantState.DENIED
        }

        val micState = if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }

        val callPhoneState = if (hasPermission(Manifest.permission.CALL_PHONE)) {
            GrantState.GRANTED
        } else {
            GrantState.DENIED
        }

        val dialerState = when {
            !VersionUtils.isAtLeastM() -> GrantState.UNSUPPORTED_ON_API
            defaultDialerManager.isDefaultDialer() -> GrantState.GRANTED
            else -> GrantState.DENIED
        }

        val batteryState = when {
            !VersionUtils.isAtLeastM() -> GrantState.NOT_REQUIRED_ON_API
            isIgnoringBatteryOptimizations() -> GrantState.GRANTED
            else -> GrantState.DENIED
        }

        return PermissionSnapshot(
            bluetooth = btState,
            phoneState = phoneState,
            notifications = notifState,
            microphone = micState,
            callPhone = callPhoneState,
            defaultDialerRole = dialerState,
            batteryOptimizationIgnored = batteryState
        )
    }

    fun requiredPermissionsFor(category: PermissionCategory): Array<String> {
        return when (category) {
            PermissionCategory.BLUETOOTH -> BlePermissions.requiredPeripheralPermissions()
            PermissionCategory.PHONE_STATE -> arrayOf(Manifest.permission.READ_PHONE_STATE)
            PermissionCategory.NOTIFICATIONS -> if (VersionUtils.isAtLeastTiramisu()) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else emptyArray()
            PermissionCategory.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            PermissionCategory.CALL_PHONE -> arrayOf(Manifest.permission.CALL_PHONE)
            PermissionCategory.DEFAULT_DIALER_ROLE,
            PermissionCategory.BATTERY_OPTIMIZATION -> emptyArray()
        }
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        if (!VersionUtils.isAtLeastM()) return true
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(appContext.packageName)
        } else true
    }

    /**
     * Builds the optional user-initiated system intent to request ignoring battery optimizations.
     * Never triggered automatically at install time.
     */
    @SuppressLint("BatteryLife")
    fun createBatteryOptimizationIntent(): Intent? {
        if (!VersionUtils.isAtLeastM()) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${appContext.packageName}")
        }
    }

    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", appContext.packageName, null)
        }
    }
}

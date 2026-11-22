package com.example.callbridge.telephony

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import com.example.callbridge.utils.VersionUtils

/**
 * Handles API-appropriate default dialer eligibility checks and role request intents:
 * - API 29+ (Android 10+): RoleManager.ROLE_DIALER
 * - API 23–28 (Android 6–9): TelecomManager.ACTION_CHANGE_DEFAULT_DIALER
 * - API 21–22 (Android 5): Public InCallService default dialer role unavailable
 */
class DefaultDialerManager(context: Context) {

    private val appContext = context.applicationContext

    fun isSupportedOnApi(): Boolean = VersionUtils.isAtLeastM()

    fun isDefaultDialer(): Boolean {
        if (!VersionUtils.isAtLeastM()) return false

        if (VersionUtils.isAtLeastQ() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = appContext.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            return telecomManager?.defaultDialerPackage == appContext.packageName
        }

        return false
    }

    /**
     * Returns the official system intent to request default dialer status, or null on API 21–22
     * or when the role is unavailable on the device (e.g., non-telephony tablet).
     */
    @Suppress("DEPRECATION")
    fun createRequestDefaultDialerIntent(): Intent? {
        if (!VersionUtils.isAtLeastM()) return null

        if (VersionUtils.isAtLeastQ() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = appContext.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, appContext.packageName)
            }
        }

        return null
    }
}

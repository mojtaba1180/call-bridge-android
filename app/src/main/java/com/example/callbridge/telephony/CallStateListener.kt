package com.example.callbridge.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.callbridge.core.Logger
import com.example.callbridge.utils.VersionUtils
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Cross-API call-state observer:
 * - API 31+ (Android 12+): TelephonyCallback.CallStateListener (note: Android intentionally omits caller number here)
 * - API 21–30 (Android 5–11): PhoneStateListener.LISTEN_CALL_STATE
 */
class CallStateListener(
    context: Context,
    private val onStateChanged: (state: CallState, bestEffortNumber: String?) -> Unit
) {
    private val appContext = context.applicationContext
    private val telephonyManager: TelephonyManager? =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val callbackExecutor: Executor = Executors.newSingleThreadExecutor()

    private var registered = false

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private var api31Callback: Any? = null

    fun start(): Boolean {
        if (registered) return true
        val tm = telephonyManager ?: run {
            Logger.w("Telephony", "TelephonyManager unavailable on this device")
            return false
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.w("Telephony", "READ_PHONE_STATE missing; call state monitoring not started")
            return false
        }

        return try {
            if (VersionUtils.isAtLeastS() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerApi31(tm)
            } else {
                registerLegacy(tm)
            }
            registered = true
            Logger.i("Telephony", "Call state listener registered (API ${VersionUtils.sdkInt})")
            true
        } catch (se: SecurityException) {
            Logger.e("Telephony", "SecurityException registering call state listener", se)
            false
        } catch (t: Throwable) {
            Logger.e("Telephony", "Failed to register call state listener", t)
            false
        }
    }

    fun stop() {
        if (!registered) return
        val tm = telephonyManager ?: return
        try {
            if (VersionUtils.isAtLeastS() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                unregisterApi31(tm)
            } else {
                unregisterLegacy(tm)
            }
        } catch (t: Throwable) {
            Logger.e("Telephony", "Error unregistering call state listener", t)
        } finally {
            registered = false
            legacyListener = null
            api31Callback = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerApi31(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                // TelephonyCallback.CallStateListener intentionally does not provide phone numbers.
                onStateChanged(mapTelephonyState(state), null)
            }
        }
        api31Callback = cb
        tm.registerTelephonyCallback(callbackExecutor, cb)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterApi31(tm: TelephonyManager) {
        val cb = api31Callback as? TelephonyCallback ?: return
        tm.unregisterTelephonyCallback(cb)
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in API 31")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val number = phoneNumber?.takeIf { it.isNotBlank() }
                onStateChanged(mapTelephonyState(state), number)
            }
        }
        legacyListener = listener
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacy(tm: TelephonyManager) {
        val listener = legacyListener ?: return
        tm.listen(listener, PhoneStateListener.LISTEN_NONE)
    }

    companion object {
        fun mapTelephonyState(state: Int): CallState = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
            TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallState.ACTIVE
            else -> CallState.UNKNOWN
        }
    }
}

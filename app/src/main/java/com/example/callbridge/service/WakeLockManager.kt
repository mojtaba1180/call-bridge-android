package com.example.callbridge.service

import android.content.Context
import android.os.PowerManager
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger

/**
 * Scoped Partial WakeLock manager with mandatory safety timeouts.
 *
 * Acquires a WakeLock ONLY during active call sessions or short critical BLE/network operations
 * and releases it immediately when no longer required. Never acquires an indefinite WakeLock.
 */
class WakeLockManager(context: Context) {

    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private val activeReasons = mutableSetOf<String>()

    fun acquireFor(reason: String, timeoutMs: Long = AppConfig.WAKELOCK_TEMP_OPERATION_MS) {
        val safeTimeout = timeoutMs.coerceIn(5_000L, AppConfig.WAKELOCK_MAX_TIMEOUT_MS)
        synchronized(lock) {
            val pm = powerManager ?: return
            activeReasons.add(reason)
            val wl = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallBridge:BridgeSessionLock"
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }

            runCatching {
                wl.acquire(safeTimeout)
                Logger.d("Power", "Partial WakeLock acquired for '$reason' (timeout=${safeTimeout}ms)")
            }.onFailure {
                Logger.w("Power", "Failed to acquire WakeLock: ${it.message}")
            }
        }
    }

    fun releaseFor(reason: String) {
        synchronized(lock) {
            activeReasons.remove(reason)
            if (activeReasons.isEmpty()) {
                releaseInternalLocked()
            }
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            activeReasons.clear()
            releaseInternalLocked()
        }
    }

    fun isHeld(): Boolean = synchronized(lock) {
        wakeLock?.isHeld == true
    }

    private fun releaseInternalLocked() {
        val wl = wakeLock ?: return
        runCatching {
            if (wl.isHeld) {
                wl.release()
                Logger.d("Power", "Partial WakeLock released")
            }
        }.onFailure {
            Logger.w("Power", "Error releasing WakeLock: ${it.message}")
        }
        wakeLock = null
    }
}

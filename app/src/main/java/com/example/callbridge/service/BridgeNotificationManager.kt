package com.example.callbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.callbridge.audio.AudioCapabilityState
import com.example.callbridge.core.Logger
import com.example.callbridge.telephony.CallInfo
import com.example.callbridge.telephony.CallState
import com.example.callbridge.ui.MainActivity
import com.example.callbridge.utils.VersionUtils

/**
 * Creates API 26+ notification channels (with API 21–25 fallback) and builds dynamic
 * persistent foreground service notifications reflecting BLE, telephony, and audio capability state.
 */
class BridgeNotificationManager(context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannelCreated() {
        if (VersionUtils.isAtLeastO() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.notif_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        isIphoneConnected: Boolean,
        callInfo: CallInfo,
        audioCapability: AudioCapabilityState
    ): Notification {
        ensureChannelCreated()

        val title = when (callInfo.state) {
            CallState.RINGING -> {
                val displayCaller = Logger.maskPhoneNumber(callInfo.phoneNumber)
                appContext.getString(R.string.notif_incoming_call, displayCaller)
            }
            CallState.OFFHOOK -> appContext.getString(R.string.notif_active_call)
            else -> if (isIphoneConnected) {
                appContext.getString(R.string.notif_connected)
            } else {
                appContext.getString(R.string.notif_waiting)
            }
        }

        val subText = if (audioCapability == AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE) {
            appContext.getString(R.string.notif_audio_unavailable)
        } else {
            appContext.getString(R.string.notif_bridge_running_subtext)
        }

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(appContext, 100, openAppIntent, pendingFlags)

        val stopIntent = Intent(appContext, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(appContext, 101, stopIntent, pendingFlags)

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(title)
            .setContentText(subText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_stat_bridge,
                appContext.getString(R.string.action_stop_bridge),
                stopPendingIntent
            )
            .build()
    }

    fun updateNotification(
        isIphoneConnected: Boolean,
        callInfo: CallInfo,
        audioCapability: AudioCapabilityState
    ) {
        val notification = buildNotification(isIphoneConnected, callInfo, audioCapability)
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Logger.w("Service", "Unable to update notification: ${it.message}")
        }
    }

    companion object {
        const val CHANNEL_ID = "callbridge_foreground_channel"
        const val NOTIFICATION_ID = 4101
    }
}

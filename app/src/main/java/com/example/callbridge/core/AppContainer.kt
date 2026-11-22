package com.example.callbridge.core

import android.content.Context
import com.example.callbridge.audio.AudioCapabilityChecker
import com.example.callbridge.audio.LocalAudioBridge
import com.example.callbridge.bluetooth.BleSignalingServer
import com.example.callbridge.network.LocalNetworkManager
import com.example.callbridge.permissions.PermissionManager
import com.example.callbridge.security.PairingManager
import com.example.callbridge.security.SessionAuthenticator
import com.example.callbridge.telephony.CallManager
import com.example.callbridge.telephony.DefaultDialerManager
import com.example.callbridge.utils.DeviceCapabilities

/**
 * Lightweight manual constructor-injection container keeping the app free of heavy DI frameworks.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val defaultDialerManager = DefaultDialerManager(appContext)
    val permissionManager = PermissionManager(appContext, defaultDialerManager)

    val pairingManager = PairingManager(appContext)
    val sessionAuthenticator = SessionAuthenticator(pairingManager)

    val callManager = CallManager(appContext, defaultDialerManager)
    val bleSignalingServer = BleSignalingServer(appContext, pairingManager, sessionAuthenticator)
    val localNetworkManager = LocalNetworkManager(appContext, sessionAuthenticator)

    val audioCapabilityChecker = AudioCapabilityChecker(appContext)
    val localAudioBridge = LocalAudioBridge(appContext)

    val deviceCapabilities = DeviceCapabilities(
        context = appContext,
        defaultDialerManager = defaultDialerManager,
        audioCapabilityChecker = audioCapabilityChecker
    )
}

package com.example.callbridge.core

import android.content.Context
import com.example.callbridge.audio.AudioCapabilityChecker
import com.example.callbridge.audio.LocalAudioBridge
import com.example.callbridge.bluetooth.BleSignalingServer
import com.example.callbridge.callhistory.CallHistoryRepository
import com.example.callbridge.contacts.ContactsProviderDataSource
import com.example.callbridge.contacts.ContactsRepository
import com.example.callbridge.database.BridgeDatabase
import com.example.callbridge.device.SimManager
import com.example.callbridge.network.LocalNetworkManager
import com.example.callbridge.permissions.PermissionManager
import com.example.callbridge.security.KeyStoreManager
import com.example.callbridge.security.PairingManager
import com.example.callbridge.security.SessionAuthenticator
import com.example.callbridge.sms.SmsBridgeManager
import com.example.callbridge.sync.SyncEngine
import com.example.callbridge.telephony.CallManager
import com.example.callbridge.telephony.DefaultDialerManager
import com.example.callbridge.utils.DeviceCapabilities

/**
 * Lightweight manual constructor-injection container wiring all BridgeLink subsystems
 * cleanly without heavy DI frameworks.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // Persistence & Security
    val database: BridgeDatabase = BridgeDatabase.getInstance(appContext)
    val keyStoreManager = KeyStoreManager(appContext)
    val pairingManager = PairingManager(
        context = appContext,
        keyStoreManager = keyStoreManager,
        pairedDeviceDao = database.pairedDeviceDao()
    )
    val sessionAuthenticator = SessionAuthenticator(pairingManager)

    // Device, Permissions & Telephony
    val defaultDialerManager = DefaultDialerManager(appContext)
    val permissionManager = PermissionManager(appContext, defaultDialerManager)
    val simManager = SimManager(appContext)

    // Data Repositories & Sync Engine
    val callHistoryRepository = CallHistoryRepository(database.callHistoryDao())
    val callManager = CallManager(appContext, defaultDialerManager, callHistoryRepository)

    val contactsDataSource = ContactsProviderDataSource(appContext)
    val contactsRepository = ContactsRepository(
        context = appContext,
        dataSource = contactsDataSource,
        metadataDao = database.contactSyncMetadataDao()
    )
    val smsBridgeManager = SmsBridgeManager(
        context = appContext,
        smsOutboxDao = database.smsOutboxDao(),
        simManager = simManager
    )
    val syncEngine = SyncEngine(
        contactsRepository = contactsRepository,
        callHistoryRepository = callHistoryRepository,
        smsOutboxDao = database.smsOutboxDao(),
        syncCursorDao = database.syncCursorDao(),
        syncSessionDao = database.syncSessionDao()
    )

    // Transports: 4-Char BLE GATT + Local TLS WebSocket & NSD
    val bleSignalingServer = BleSignalingServer(
        context = appContext,
        pairingManager = pairingManager,
        sessionAuthenticator = sessionAuthenticator,
        keyStoreManager = keyStoreManager
    )
    val localNetworkManager = LocalNetworkManager(
        context = appContext,
        keyStoreManager = keyStoreManager,
        pairingManager = pairingManager,
        sessionAuthenticator = sessionAuthenticator
    )

    // Isolated Audio & Diagnostics
    val audioCapabilityChecker = AudioCapabilityChecker(appContext)
    val localAudioBridge = LocalAudioBridge(appContext)
    val deviceCapabilities = DeviceCapabilities(
        context = appContext,
        defaultDialerManager = defaultDialerManager,
        audioCapabilityChecker = audioCapabilityChecker,
        simManager = simManager,
        keyStoreManager = keyStoreManager
    )
}

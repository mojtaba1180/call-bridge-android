package com.example.callbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.BleFrameReassembler
import com.example.callbridge.protocol.BlePacketFramer
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandResultMessage
import com.example.callbridge.protocol.CommandValidationResult
import com.example.callbridge.protocol.MessageSerializer
import com.example.callbridge.protocol.PairingStatusMessage
import com.example.callbridge.protocol.ProtocolConstants
import com.example.callbridge.security.PairingManager
import com.example.callbridge.security.SessionAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class BleStatusSnapshot(
    val bleHardwareSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val peripheralSupported: Boolean,
    val gattServerInitialized: Boolean,
    val advertisingStatus: AdvertisingStatus,
    val connectedDeviceAddress: String?,
    val connectedDeviceName: String?,
    val negotiatedMtu: Int,
    val isClientSubscribed: Boolean,
    val isClientAuthenticated: Boolean,
    val lastError: String?
)

/**
 * BLE Peripheral / GATT Server responsible for discovery, pairing handshake, call-state notifications,
 * and authenticated command processing for the paired iPhone Central (CoreBluetooth).
 */
class BleSignalingServer(
    context: Context,
    private val pairingManager: PairingManager,
    private val sessionAuthenticator: SessionAuthenticator
) : BleGattEventHandler {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val advertisingManager = BleAdvertisingManager(appContext) { bluetoothManager?.adapter }

    private val reassembler = BleFrameReassembler()
    private val notifyMutex = Mutex()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, Boolean>()
    private val deviceMtus = ConcurrentHashMap<String, Int>()

    private var serverScope: CoroutineScope? = null
    private var gattServer: BluetoothGattServer? = null
    private var callStateCharacteristic: BluetoothGattCharacteristic? = null
    private var latestCallStateJson: String = ""
    private var commandHandler: (suspend (CommandMessage, String) -> CommandResultMessage)? = null

    private val _status = MutableStateFlow(buildStatusSnapshot())
    val status: StateFlow<BleStatusSnapshot> = _status.asStateFlow()

    fun setCommandHandler(handler: suspend (CommandMessage, String) -> CommandResultMessage) {
        commandHandler = handler
    }

    fun refreshStatus(): BleStatusSnapshot {
        val snap = buildStatusSnapshot()
        _status.value = snap
        return snap
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (gattServer != null) {
            Logger.d("BLE", "GATT server already initialized; preventing duplicate start")
            refreshStatus()
            return true
        }

        val hasBleHardware = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if (!hasBleHardware) {
            Logger.w("BLE", "Device does not report Bluetooth LE hardware support")
            refreshStatus()
            return false
        }

        if (!BlePermissions.hasPeripheralPermissions(appContext)) {
            Logger.w("BLE", "BLE peripheral permissions missing; cannot initialize GATT server")
            refreshStatus()
            return false
        }

        val manager = bluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            Logger.w("BLE", "Bluetooth adapter unavailable or disabled")
            refreshStatus()
            return false
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope

        return try {
            val callbacks = BleGattCallbacks(this)
            val server = manager.openGattServer(appContext, callbacks)
            if (server == null) {
                Logger.e("BLE", "openGattServer returned null")
                scope.cancel()
                serverScope = null
                refreshStatus()
                return false
            }

            gattServer = server
            registerCallBridgeService(server)

            scope.launch {
                advertisingManager.status.collect {
                    refreshStatus()
                }
            }
            advertisingManager.startAdvertising(scope)
            refreshStatus()
            Logger.i("BLE", "GATT server initialized with custom CallBridge service")
            true
        } catch (se: SecurityException) {
            Logger.e("BLE", "SecurityException initializing GATT server", se)
            stop()
            false
        } catch (t: Throwable) {
            Logger.e("BLE", "Failed to start GATT server", t)
            stop()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertisingManager.stopAdvertising()

        val server = gattServer
        gattServer = null
        callStateCharacteristic = null

        if (server != null && BlePermissions.hasConnectPermission(appContext)) {
            connectedDevices.values.forEach { device ->
                runCatching { server.cancelConnection(device) }
            }
            runCatching { server.clearServices() }
            runCatching { server.close() }
        }

        connectedDevices.clear()
        subscribedDevices.clear()
        deviceMtus.clear()
        reassembler.clearAll()
        sessionAuthenticator.invalidateSession("BLE server stopped")

        serverScope?.cancel()
        serverScope = null
        refreshStatus()
        Logger.i("BLE", "GATT server stopped and cleaned up")
    }

    /**
     * Broadcasts the latest CALL_STATE payload to all subscribed BLE clients with MTU-aware framing.
     */
    fun publishCallState(message: CallStateMessage) {
        val json = MessageSerializer.serializeCallState(message)
        latestCallStateJson = json
        val scope = serverScope ?: return
        scope.launch {
            sendFramedNotificationToSubscribed(json.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Sends a pairing status update (e.g., after the Android user explicitly approves or rejects pairing).
     */
    fun notifyPairingStatus(statusMessage: PairingStatusMessage) {
        val json = MessageSerializer.serializePairingStatus(statusMessage)
        val scope = serverScope ?: return
        scope.launch {
            sendFramedNotificationToSubscribed(json.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Runs a real diagnostic check of Bluetooth hardware, adapter state, peripheral advertising support,
     * and GATT service registration.
     */
    fun runSelfTest(): String {
        val snap = refreshStatus()
        val summary = buildString {
            append("BLE HW=").append(snap.bleHardwareSupported)
            append(", BT Enabled=").append(snap.bluetoothEnabled)
            append(", Peripheral=").append(snap.peripheralSupported)
            append(", GATT=").append(snap.gattServerInitialized)
            append(", Adv=").append(snap.advertisingStatus.name)
            append(", Clients=").append(connectedDevices.size)
        }
        Logger.i("BLE", "BLE self-test: $summary")
        return summary
    }

    @SuppressLint("MissingPermission")
    private fun registerCallBridgeService(server: BluetoothGattServer) {
        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val callStateChar = BluetoothGattCharacteristic(
            BleConstants.CALL_STATE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        callStateChar.addDescriptor(cccd)

        val commandChar = BluetoothGattCharacteristic(
            BleConstants.COMMAND_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(callStateChar)
        service.addCharacteristic(commandChar)

        callStateCharacteristic = callStateChar
        server.addService(service)
    }

    @SuppressLint("MissingPermission")
    override fun onClientConnectionStateChanged(device: BluetoothDevice, status: Int, newState: Int) {
        val address = device.address ?: "unknown"
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Logger.w("BLE", "GATT connection state error (status=$status, addr=$address)")
        }

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                connectedDevices[address] = device
                deviceMtus.putIfAbsent(address, AppConfig.BLE_DEFAULT_MTU)
                Logger.i("BLE", "GATT client connected ($address)")
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                connectedDevices.remove(address)
                subscribedDevices.remove(address)
                deviceMtus.remove(address)
                reassembler.clearDevice(address)
                if (connectedDevices.isEmpty()) {
                    sessionAuthenticator.invalidateSession("GATT client disconnected")
                }
                Logger.i("BLE", "GATT client disconnected ($address)")
            }
        }
        refreshStatus()
    }

    override fun onServiceAddedResult(status: Int, service: BluetoothGattService?) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Logger.i("BLE", "GATT service registered: ${service?.uuid}")
        } else {
            Logger.e("BLE", "Failed to register GATT service (status=$status)")
        }
        refreshStatus()
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        val server = gattServer ?: return
        if (!BlePermissions.hasConnectPermission(appContext)) return

        if (characteristic.uuid == BleConstants.CALL_STATE_CHAR_UUID) {
            val payload = latestCallStateJson.toByteArray(Charsets.UTF_8)
            val slice = if (offset >= payload.size) ByteArray(0) else payload.copyOfRange(offset, payload.size)
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        } else {
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWrite(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val server = gattServer ?: return
        if (!BlePermissions.hasConnectPermission(appContext)) return

        if (characteristic.uuid != BleConstants.COMMAND_CHAR_UUID) {
            if (responseNeeded) {
                runCatching {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                }
            }
            return
        }

        if (responseNeeded) {
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        val packet = value ?: return
        val address = device.address ?: "unknown"
        val assembled = reassembler.onPacketReceived(address, packet) ?: return

        val rawJson = assembled.toString(Charsets.UTF_8)
        serverScope?.launch {
            handleIncomingCommandJson(device, address, rawJson)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        val server = gattServer ?: return
        if (!BlePermissions.hasConnectPermission(appContext)) return

        if (descriptor.uuid == BleConstants.CCCD_UUID) {
            val enabled = subscribedDevices[device.address] == true
            val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        } else {
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWrite(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        val server = gattServer ?: return
        if (!BlePermissions.hasConnectPermission(appContext)) return

        if (descriptor.uuid == BleConstants.CCCD_UUID) {
            val enabled = value != null &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
            subscribedDevices[device.address ?: "unknown"] = enabled
            if (responseNeeded) {
                runCatching {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
            Logger.i("BLE", "Client ${if (enabled) "subscribed to" else "unsubscribed from"} CALL_STATE notifications")
            refreshStatus()

            if (enabled && latestCallStateJson.isNotEmpty()) {
                serverScope?.launch {
                    sendFramedNotificationToDevice(device, latestCallStateJson.toByteArray(Charsets.UTF_8))
                }
            }
        } else if (responseNeeded) {
            runCatching {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
            }
        }
    }

    override fun onDeviceMtuChanged(device: BluetoothDevice, mtu: Int) {
        val address = device.address ?: "unknown"
        deviceMtus[address] = mtu.coerceAtLeast(AppConfig.BLE_DEFAULT_MTU)
        Logger.i("BLE", "MTU negotiated with $address: $mtu bytes")
        refreshStatus()
    }

    override fun onNotificationSentResult(device: BluetoothDevice, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Logger.w("BLE", "GATT notification delivery status=$status for ${device.address}")
        }
    }

    private suspend fun handleIncomingCommandJson(device: BluetoothDevice, address: String, rawJson: String) {
        val validation = MessageSerializer.parseAndValidateCommand(rawJson)
        val result: CommandResultMessage = when (validation) {
            is CommandValidationResult.Invalid -> {
                Logger.w("BLE", "Rejected invalid BLE command: ${validation.errorCode}")
                CommandResultMessage(
                    id = validation.commandId,
                    success = false,
                    message = validation.reason,
                    errorCode = validation.errorCode
                )
            }
            is CommandValidationResult.Valid -> {
                processValidatedCommand(validation.command, address)
            }
        }

        val responseBytes = MessageSerializer.serializeCommandResult(result).toByteArray(Charsets.UTF_8)
        sendFramedNotificationToDevice(device, responseBytes)
        refreshStatus()
    }

    private suspend fun processValidatedCommand(command: CommandMessage, address: String): CommandResultMessage {
        Logger.i("BLE", "Command received: ${command.cmd} (id=${command.id})")

        return when (command.cmd) {
            ProtocolConstants.CMD_PING -> CommandResultMessage(
                id = command.id,
                success = true,
                message = "PONG"
            )

            ProtocolConstants.CMD_PAIR_REQUEST -> {
                val deviceId = command.deviceId ?: return CommandResultMessage(
                    id = command.id,
                    success = false,
                    message = "Missing deviceId",
                    errorCode = "MISSING_DEVICE_ID"
                )
                val req = pairingManager.registerIncomingPairRequest(deviceId, command.deviceName, address)
                CommandResultMessage(
                    id = command.id,
                    success = true,
                    message = "Pairing request pending user approval on Android (enter displayed 6-digit PIN if prompted)",
                    capabilityStatus = "AWAITING_USER_APPROVAL:${req.deviceId}"
                )
            }

            ProtocolConstants.CMD_PAIR_VERIFY -> {
                val ok = pairingManager.verifyPinForPairing(
                    deviceId = command.deviceId.orEmpty(),
                    deviceName = command.deviceName,
                    bleAddress = address,
                    suppliedPin = command.pinCode.orEmpty()
                )
                if (ok) {
                    CommandResultMessage(
                        id = command.id,
                        success = true,
                        message = "PIN verified; awaiting explicit user confirmation on Android"
                    )
                } else {
                    CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "Invalid or expired pairing PIN",
                        errorCode = "PAIRING_PIN_INVALID"
                    )
                }
            }

            ProtocolConstants.CMD_AUTH -> {
                val session = sessionAuthenticator.authenticatePairedDevice(command.deviceId, address)
                if (session != null) {
                    CommandResultMessage(
                        id = command.id,
                        success = true,
                        message = "Session authenticated",
                        sessionToken = session.sessionToken
                    )
                } else {
                    val code = if (sessionAuthenticator.isInCooldown()) "AUTH_COOLDOWN" else "UNAUTHORIZED_DEVICE"
                    CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "Device must be paired and approved by the Android user first",
                        errorCode = code
                    )
                }
            }

            else -> {
                if (command.cmd in ProtocolConstants.SENSITIVE_COMMANDS &&
                    !sessionAuthenticator.isAuthorized(command.sessionToken, address)
                ) {
                    Logger.w("Security", "Blocked unauthorized sensitive command ${command.cmd} from $address")
                    return CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "Authentication required before executing ${command.cmd}",
                        errorCode = "UNAUTHORIZED"
                    )
                }

                val handler = commandHandler
                if (handler != null) {
                    handler(command, address)
                } else {
                    CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "Bridge command processor unavailable",
                        errorCode = "HANDLER_UNAVAILABLE"
                    )
                }
            }
        }
    }

    private suspend fun sendFramedNotificationToSubscribed(payload: ByteArray) {
        val targets = connectedDevices.entries
            .filter { (addr, _) -> subscribedDevices[addr] == true }
            .map { it.value }
        for (device in targets) {
            sendFramedNotificationToDevice(device, payload)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun sendFramedNotificationToDevice(device: BluetoothDevice, payload: ByteArray) {
        val server = gattServer ?: return
        val characteristic = callStateCharacteristic ?: return
        if (!BlePermissions.hasConnectPermission(appContext)) return

        val address = device.address ?: return
        val mtu = deviceMtus[address] ?: AppConfig.BLE_DEFAULT_MTU
        val frames = runCatching { BlePacketFramer.fragment(payload, mtu) }.getOrNull() ?: return

        notifyMutex.withLock {
            for (frame in frames) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(device, characteristic, false, frame)
                    } else {
                        characteristic.value = frame
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    }
                    if (frames.size > 1) {
                        delay(15L) // Pace multi-frame bursts to avoid GATT buffer overflow on legacy stacks
                    }
                } catch (se: SecurityException) {
                    Logger.e("BLE", "SecurityException sending notification", se)
                    break
                } catch (t: Throwable) {
                    Logger.e("BLE", "Error sending BLE notification frame", t)
                    break
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildStatusSnapshot(): BleStatusSnapshot {
        val bleHw = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = bluetoothManager?.adapter
        val enabled = adapter?.isEnabled == true
        val peripheralSupported = advertisingManager.isPeripheralAdvertisingSupported()
        val firstClient = connectedDevices.values.firstOrNull()
        val firstAddr = firstClient?.address
        val clientName = if (firstClient != null && BlePermissions.hasConnectPermission(appContext)) {
            runCatching { firstClient.name }.getOrNull()
        } else null
        val mtu = firstAddr?.let { deviceMtus[it] } ?: AppConfig.BLE_DEFAULT_MTU
        val subscribed = firstAddr != null && subscribedDevices[firstAddr] == true

        return BleStatusSnapshot(
            bleHardwareSupported = bleHw,
            bluetoothEnabled = enabled,
            peripheralSupported = peripheralSupported,
            gattServerInitialized = gattServer != null,
            advertisingStatus = advertisingManager.status.value,
            connectedDeviceAddress = firstAddr,
            connectedDeviceName = clientName,
            negotiatedMtu = mtu,
            isClientSubscribed = subscribed,
            isClientAuthenticated = sessionAuthenticator.activeSession.value != null,
            lastError = advertisingManager.lastError.value
        )
    }
}

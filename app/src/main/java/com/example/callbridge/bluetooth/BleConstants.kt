package com.example.callbridge.bluetooth

import java.util.UUID

/**
 * Stable custom 128-bit UUIDs matching BRIDGELINK_PROTOCOL.md shared between
 * the BridgeLink Android BLE Peripheral and future iOS CoreBluetooth Central.
 */
object BleConstants {
    /** Custom BridgeLink GATT Primary Service UUID */
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 1: DEVICE_INFO (READ) - Android device identity, capabilities, WSS endpoint & cert pin */
    val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("6E400002-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 2: EVENT_STREAM (READ | NOTIFY + CCCD) - live call states, command results, sync notices */
    val EVENT_STREAM_CHAR_UUID: UUID = UUID.fromString("6E400003-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 3: COMMAND (WRITE | WRITE_NO_RESPONSE) - framed JSON commands from iPhone */
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("6E400004-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 4: PAIRING (READ | WRITE | NOTIFY + CCCD) - QR token / PIN pairing handshake */
    val PAIRING_CHAR_UUID: UUID = UUID.fromString("6E400005-C352-11E5-953D-0002A5D5C51B")

    /** Standard Bluetooth SIG Client Characteristic Configuration Descriptor (CCCD) for NOTIFY subscription */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val ADVERTISED_LOCAL_NAME_PREFIX = "BridgeLink"
}

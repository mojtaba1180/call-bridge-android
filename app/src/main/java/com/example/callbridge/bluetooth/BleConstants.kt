package com.example.callbridge.bluetooth

import java.util.UUID

/**
 * Stable custom 128-bit UUIDs shared between the Android BLE Peripheral and future iOS CoreBluetooth Central.
 * Do NOT change these UUIDs across application releases.
 */
object BleConstants {
    /** Custom CallBridge GATT Service UUID */
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 1: CALL_STATE_CHAR (READ | NOTIFY) - sends call state, endpoint, command results, pairing updates */
    val CALL_STATE_CHAR_UUID: UUID = UUID.fromString("6E400002-C352-11E5-953D-0002A5D5C51B")

    /** Characteristic 2: COMMAND_CHAR (WRITE | WRITE_NO_RESPONSE) - receives JSON commands from iPhone */
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("6E400003-C352-11E5-953D-0002A5D5C51B")

    /** Standard Bluetooth SIG Client Characteristic Configuration Descriptor (CCCD) for NOTIFY subscription */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val ADVERTISED_LOCAL_NAME_PREFIX = "CallBridge"
}

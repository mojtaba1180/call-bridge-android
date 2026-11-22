package com.example.callbridge.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService

/**
 * Clean delegate bridge between Android's [BluetoothGattServerCallback] and [BleSignalingServer].
 */
interface BleGattEventHandler {
    fun onClientConnectionStateChanged(device: BluetoothDevice, status: Int, newState: Int)
    fun onServiceAddedResult(status: Int, service: BluetoothGattService?)
    fun onCharacteristicRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    )
    fun onCharacteristicWrite(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    )
    fun onDescriptorRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    )
    fun onDescriptorWrite(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    )
    fun onDeviceMtuChanged(device: BluetoothDevice, mtu: Int)
    fun onNotificationSentResult(device: BluetoothDevice, status: Int)
}

class BleGattCallbacks(
    private val handler: BleGattEventHandler
) : BluetoothGattServerCallback() {

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        handler.onClientConnectionStateChanged(device, status, newState)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
        handler.onServiceAddedResult(status, service)
    }

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        handler.onCharacteristicRead(device, requestId, offset, characteristic)
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        handler.onCharacteristicWrite(
            device,
            requestId,
            characteristic,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        handler.onDescriptorRead(device, requestId, offset, descriptor)
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        handler.onDescriptorWrite(
            device,
            requestId,
            descriptor,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        handler.onDeviceMtuChanged(device, mtu)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        handler.onNotificationSentResult(device, status)
    }
}

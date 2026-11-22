package com.example.callbridge.protocol

import com.example.callbridge.core.AppConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Marker interface for all protocol messages exchanged between Android Companion and iOS.
 */
sealed interface BridgeMessage

/**
 * Validation result for incoming commands.
 */
sealed class CommandValidationResult {
    data class Valid(val command: CommandMessage) : CommandValidationResult()
    data class Invalid(val commandId: String, val errorCode: String, val reason: String) : CommandValidationResult()
}

/**
 * Handles framing and reassembly of arbitrary-length UTF-8 JSON payloads over BLE GATT notifications
 * and characteristic writes whose effective payload size depends on the negotiated ATT MTU.
 *
 * Frame layout:
 *  Byte 0: FRAME_MAGIC (0xCB)
 *  Byte 1: messageId (wrapping 0..255)
 *  Byte 2: frameIndex (0..totalFrames-1)
 *  Byte 3: totalFrames (1..MAX_FRAME_COUNT)
 *  Byte 4..N: payload slice
 *
 * Backward-compatible convenience: if an incoming write begins with '{' (unframed single-packet JSON),
 * [BleFrameReassembler] accepts it directly when complete.
 */
object BlePacketFramer {

    private val messageIdCounter = AtomicInteger(0)

    fun fragment(payload: ByteArray, mtu: Int): List<ByteArray> {
        val effectiveChunkLimit = (mtu - AppConfig.BLE_ATT_HEADER_OVERHEAD).coerceAtLeast(20)
        val maxDataPerFrame = (effectiveChunkLimit - ProtocolConstants.FRAME_HEADER_SIZE).coerceAtLeast(16)

        if (payload.isEmpty()) {
            return listOf(byteArrayOf(ProtocolConstants.FRAME_MAGIC, nextMsgId(), 0, 1))
        }

        val totalFrames = ((payload.size + maxDataPerFrame - 1) / maxDataPerFrame)
            .coerceAtMost(ProtocolConstants.MAX_FRAME_COUNT)
        require(payload.size <= totalFrames * maxDataPerFrame) {
            "Payload size ${payload.size} exceeds max framed capacity for MTU $mtu"
        }

        val msgId = nextMsgId()
        val frames = ArrayList<ByteArray>(totalFrames)
        var offset = 0
        for (index in 0 until totalFrames) {
            val length = minOf(maxDataPerFrame, payload.size - offset)
            val frame = ByteArray(ProtocolConstants.FRAME_HEADER_SIZE + length)
            frame[0] = ProtocolConstants.FRAME_MAGIC
            frame[1] = msgId
            frame[2] = index.toByte()
            frame[3] = totalFrames.toByte()
            System.arraycopy(payload, offset, frame, ProtocolConstants.FRAME_HEADER_SIZE, length)
            frames.add(frame)
            offset += length
        }
        return frames
    }

    private fun nextMsgId(): Byte = (messageIdCounter.incrementAndGet() and 0xFF).toByte()
}

/**
 * Reassembles framed or raw JSON packets per connected BLE device.
 */
class BleFrameReassembler(
    private val maxReassemblyBytes: Int = AppConfig.BLE_MAX_REASSEMBLY_BYTES
) {
    private data class PartialMessage(
        val messageId: Int,
        val totalFrames: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var totalBytes: Int = 0
    )

    private val partialByDevice = mutableMapOf<String, PartialMessage>()

    @Synchronized
    fun onPacketReceived(deviceAddress: String, packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null

        // Allow unframed single-MTU JSON writes for simple testing tools / CoreBluetooth short writes
        if (packet[0] == '{'.code.toByte()) {
            return if (packet.size <= maxReassemblyBytes) packet else null
        }

        if (packet.size < ProtocolConstants.FRAME_HEADER_SIZE ||
            packet[0] != ProtocolConstants.FRAME_MAGIC
        ) {
            return null
        }

        val msgId = packet[1].toInt() and 0xFF
        val frameIndex = packet[2].toInt() and 0xFF
        val totalFrames = packet[3].toInt() and 0xFF

        if (totalFrames <= 0 || totalFrames > ProtocolConstants.MAX_FRAME_COUNT || frameIndex >= totalFrames) {
            partialByDevice.remove(deviceAddress)
            return null
        }

        val dataSlice = packet.copyOfRange(ProtocolConstants.FRAME_HEADER_SIZE, packet.size)

        val current = partialByDevice[deviceAddress]
        val partial = if (current == null || current.messageId != msgId || current.totalFrames != totalFrames) {
            PartialMessage(msgId, totalFrames).also { partialByDevice[deviceAddress] = it }
        } else {
            current
        }

        if (!partial.chunks.containsKey(frameIndex)) {
            partial.totalBytes += dataSlice.size
            if (partial.totalBytes > maxReassemblyBytes) {
                partialByDevice.remove(deviceAddress)
                return null
            }
            partial.chunks[frameIndex] = dataSlice
        }

        if (partial.chunks.size == partial.totalFrames) {
            partialByDevice.remove(deviceAddress)
            val output = ByteArrayOutputStream(partial.totalBytes)
            for (i in 0 until partial.totalFrames) {
                val chunk = partial.chunks[i] ?: return null
                output.write(chunk)
            }
            return output.toByteArray()
        }
        return null
    }

    @Synchronized
    fun clearDevice(deviceAddress: String) {
        partialByDevice.remove(deviceAddress)
    }

    @Synchronized
    fun clearAll() {
        partialByDevice.clear()
    }
}

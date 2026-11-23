package com.example.callbridge.protocol

import com.example.callbridge.core.AppConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Marker interface for all protocol messages exchanged between BridgeLink Android and iOS.
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
 * Handles MTU-aware chunking of arbitrary-length UTF-8 JSON payloads over BLE GATT characteristics
 * using the 8-byte BridgeLink binary framing header:
 *
 * - Byte 0:    magic (`0xB1`)
 * - Byte 1:    protocol version (`1`)
 * - Byte 2..3: uint16 big-endian `messageId` (1..65535)
 * - Byte 4:    `chunkIndex` (0..totalChunks-1)
 * - Byte 5:    `totalChunks` (1..64)
 * - Byte 6..7: uint16 big-endian `payloadLength`
 * - Byte 8..N: UTF-8 JSON payload fragment
 */
object BlePacketFramer {

    private val messageIdCounter = AtomicInteger(0)

    fun fragment(payload: ByteArray, mtu: Int, explicitMessageId: Int? = null): List<ByteArray> {
        val effectiveChunkLimit = (mtu - AppConfig.BLE_ATT_HEADER_OVERHEAD).coerceAtLeast(20)
        val maxDataPerFrame = (effectiveChunkLimit - ProtocolConstants.FRAME_HEADER_SIZE).coerceAtLeast(12)
        val msgId = (explicitMessageId ?: nextMsgId()) and 0xFFFF

        if (payload.isEmpty()) {
            return listOf(buildHeader(msgId, 0, 1, 0))
        }

        val totalFrames = (payload.size + maxDataPerFrame - 1) / maxDataPerFrame
        require(totalFrames in 1..ProtocolConstants.MAX_FRAME_COUNT) {
            "Payload size ${payload.size} exceeds max BLE framed capacity ($totalFrames chunks for MTU $mtu)"
        }

        val frames = ArrayList<ByteArray>(totalFrames)
        var offset = 0
        for (index in 0 until totalFrames) {
            val length = minOf(maxDataPerFrame, payload.size - offset)
            val frame = ByteArray(ProtocolConstants.FRAME_HEADER_SIZE + length)
            val header = buildHeader(msgId, index, totalFrames, length)
            System.arraycopy(header, 0, frame, 0, ProtocolConstants.FRAME_HEADER_SIZE)
            System.arraycopy(payload, offset, frame, ProtocolConstants.FRAME_HEADER_SIZE, length)
            frames.add(frame)
            offset += length
        }
        return frames
    }

    private fun buildHeader(msgId: Int, chunkIndex: Int, totalChunks: Int, payloadLength: Int): ByteArray {
        return byteArrayOf(
            ProtocolConstants.FRAME_MAGIC,
            ProtocolConstants.VERSION.toByte(),
            ((msgId ushr 8) and 0xFF).toByte(),
            (msgId and 0xFF).toByte(),
            (chunkIndex and 0xFF).toByte(),
            (totalChunks and 0xFF).toByte(),
            ((payloadLength ushr 8) and 0xFF).toByte(),
            (payloadLength and 0xFF).toByte()
        )
    }

    private fun nextMsgId(): Int {
        val next = messageIdCounter.incrementAndGet() and 0xFFFF
        return if (next == 0) messageIdCounter.incrementAndGet() and 0xFFFF else next
    }
}

/**
 * Reassembles 8-byte framed BLE packets (or unframed single-MTU JSON writes starting with '{')
 * per connected BLE device, with a 64-entry sliding window for duplicate `messageId` suppression.
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
    private val recentMessageIdsByDevice = mutableMapOf<String, LinkedHashSet<Int>>()

    @Synchronized
    fun onPacketReceived(deviceAddress: String, packet: ByteArray): ByteArray? {
        if (packet.isEmpty()) return null

        // Allow unframed single-MTU JSON writes for diagnostic tools & CoreBluetooth short writes
        if (packet[0] == '{'.code.toByte()) {
            return if (packet.size <= maxReassemblyBytes) packet else null
        }

        if (packet.size < ProtocolConstants.FRAME_HEADER_SIZE ||
            packet[0] != ProtocolConstants.FRAME_MAGIC
        ) {
            return null
        }

        val version = packet[1].toInt() and 0xFF
        if (version != ProtocolConstants.VERSION) {
            return null
        }

        val msgId = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val frameIndex = packet[4].toInt() and 0xFF
        val totalFrames = packet[5].toInt() and 0xFF
        val declaredPayloadLen = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

        if (totalFrames <= 0 || totalFrames > ProtocolConstants.MAX_FRAME_COUNT || frameIndex >= totalFrames) {
            partialByDevice.remove(deviceAddress)
            return null
        }

        val availableBytes = packet.size - ProtocolConstants.FRAME_HEADER_SIZE
        if (declaredPayloadLen < 0 || declaredPayloadLen > availableBytes) {
            partialByDevice.remove(deviceAddress)
            return null
        }

        val recentIds = recentMessageIdsByDevice.getOrPut(deviceAddress) { LinkedHashSet() }
        if (msgId != 0 && recentIds.contains(msgId)) {
            // Duplicate message already delivered within sliding window — drop idempotently
            return null
        }

        val dataSlice = packet.copyOfRange(
            ProtocolConstants.FRAME_HEADER_SIZE,
            ProtocolConstants.FRAME_HEADER_SIZE + declaredPayloadLen
        )

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
            if (msgId != 0) {
                if (recentIds.size >= ProtocolConstants.DUPLICATE_MSG_WINDOW) {
                    recentIds.firstOrNull()?.let { recentIds.remove(it) }
                }
                recentIds.add(msgId)
            }
            return output.toByteArray()
        }
        return null
    }

    @Synchronized
    fun clearDevice(deviceAddress: String) {
        partialByDevice.remove(deviceAddress)
        recentMessageIdsByDevice.remove(deviceAddress)
    }

    @Synchronized
    fun clearAll() {
        partialByDevice.clear()
        recentMessageIdsByDevice.clear()
    }
}

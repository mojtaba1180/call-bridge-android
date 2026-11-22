package com.example.callbridge.audio

import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Modular real-time audio transport abstraction designed to support future secure transports
 * such as WebRTC or DTLS-SRTP without rewriting [LocalAudioBridge].
 */
interface AudioTransport {
    val isEncryptedProductionReady: Boolean
    fun open(localPort: Int, remoteHost: String? = null, remotePort: Int = localPort): Boolean
    fun sendPacket(packet: AudioPacket): Boolean
    fun receivePacket(timeoutMs: Int): AudioPacket?
    fun close()
}

/**
 * DEVELOPMENT / TEST MODE ONLY PCM UDP transport.
 *
 * Includes sequence numbers and millisecond timestamps for packet-loss and latency measurement.
 * Explicitly marked non-production (`isEncryptedProductionReady = false`) so unencrypted UDP
 * is never mistaken for encrypted production phone-call audio. Production builds should plug in
 * a standard WebRTC / DTLS-SRTP [AudioTransport] implementation.
 */
class DevUdpPcmAudioTransport : AudioTransport {

    override val isEncryptedProductionReady: Boolean = false

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = AppConfig.DEFAULT_DEV_AUDIO_UDP_PORT

    override fun open(localPort: Int, remoteHost: String?, remotePort: Int): Boolean {
        close()
        return try {
            val s = DatagramSocket(localPort).apply {
                reuseAddress = true
            }
            socket = s
            targetPort = remotePort
            targetAddress = remoteHost?.takeIf { it.isNotBlank() }?.let { InetAddress.getByName(it) }
            Logger.i("Audio", "DEV UDP PCM transport opened on port $localPort (test mode only, unencrypted)")
            true
        } catch (t: Throwable) {
            Logger.e("Audio", "Failed to open DEV UDP transport on port $localPort", t)
            false
        }
    }

    override fun sendPacket(packet: AudioPacket): Boolean {
        val s = socket ?: return false
        val dest = targetAddress ?: return false
        return try {
            val raw = encodePacket(packet)
            val dg = DatagramPacket(raw, raw.size, dest, targetPort)
            s.send(dg)
            true
        } catch (t: Throwable) {
            Logger.w("Audio", "DEV UDP send error: ${t.message}")
            false
        }
    }

    override fun receivePacket(timeoutMs: Int): AudioPacket? {
        val s = socket ?: return null
        return try {
            s.soTimeout = timeoutMs.coerceAtLeast(10)
            val buf = ByteArray(HEADER_BYTES + AppConfig.AUDIO_FRAME_BYTES * 4)
            val dg = DatagramPacket(buf, buf.size)
            s.receive(dg)
            if (targetAddress == null && dg.address != null) {
                targetAddress = dg.address
                targetPort = dg.port
            }
            decodePacket(dg.data, dg.length)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        targetAddress = null
    }

    companion object {
        const val HEADER_BYTES = 16
        private const val MAGIC_0: Byte = 0xCA.toByte()
        private const val MAGIC_1: Byte = 0xB1.toByte()
        private const val FLAG_DEV_MIC_ONLY: Short = 0x0001

        fun encodePacket(packet: AudioPacket): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_BYTES + packet.pcmData.size)
                .order(ByteOrder.BIG_ENDIAN)
            buffer.put(MAGIC_0)
            buffer.put(MAGIC_1)
            buffer.putShort(if (packet.isDevMicrophoneAudio) FLAG_DEV_MIC_ONLY else 0)
            buffer.putInt(packet.sequenceNumber)
            buffer.putLong(packet.timestampMs)
            buffer.put(packet.pcmData)
            return buffer.array()
        }

        fun decodePacket(raw: ByteArray, length: Int = raw.size): AudioPacket? {
            if (length < HEADER_BYTES) return null
            if (raw[0] != MAGIC_0 || raw[1] != MAGIC_1) return null
            val buffer = ByteBuffer.wrap(raw, 0, length).order(ByteOrder.BIG_ENDIAN)
            buffer.short // skip magic
            val flags = buffer.short
            val seq = buffer.int
            val ts = buffer.long
            val pcmLen = length - HEADER_BYTES
            val pcm = ByteArray(pcmLen)
            buffer.get(pcm)
            return AudioPacket(
                sequenceNumber = seq,
                timestampMs = ts,
                pcmData = pcm,
                isDevMicrophoneAudio = (flags.toInt() and FLAG_DEV_MIC_ONLY.toInt()) != 0
            )
        }
    }
}

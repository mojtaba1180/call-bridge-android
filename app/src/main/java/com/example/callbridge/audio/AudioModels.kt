package com.example.callbridge.audio

import android.media.AudioFormat
import com.example.callbridge.core.AppConfig

/**
 * Explicit cellular audio bridge capability states.
 * Normal third-party Android applications MUST report [CELLULAR_AUDIO_UNAVAILABLE]
 * (or [MICROPHONE_TEST_AVAILABLE] for local microphone test mode only) rather than
 * pretending microphone audio equals direct cellular uplink/downlink bridging.
 */
enum class AudioCapabilityState {
    CELLULAR_AUDIO_UNAVAILABLE,
    MICROPHONE_TEST_AVAILABLE,
    CELLULAR_AUDIO_EXPERIMENTAL,
    CELLULAR_AUDIO_SUPPORTED
}

/**
 * Distinguishes microphone loopback/dev testing from actual cellular call audio bridging.
 */
enum class AudioBridgeMode {
    IDLE,
    MICROPHONE_LOOPBACK_TEST,
    MICROPHONE_DEV_UDP_TEST
}

data class AudioFormatConfig(
    val sampleRateHz: Int = AppConfig.AUDIO_SAMPLE_RATE_HZ,
    val channelInConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val channelOutConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val frameDurationMs: Int = AppConfig.AUDIO_FRAME_DURATION_MS
) {
    val frameSamples: Int
        get() = (sampleRateHz * frameDurationMs) / 1000

    val frameBytes: Int
        get() = frameSamples * AppConfig.AUDIO_BYTES_PER_SAMPLE
}

data class AudioEffectsAvailability(
    val acousticEchoCancelerAvailable: Boolean,
    val noiseSuppressorAvailable: Boolean,
    val automaticGainControlAvailable: Boolean
)

data class AudioCapabilityReport(
    val microphonePermissionGranted: Boolean,
    val audioRecordAvailable: Boolean,
    val audioTrackAvailable: Boolean,
    val effectsAvailability: AudioEffectsAvailability,
    val cellularAudioCapability: AudioCapabilityState,
    val microphoneTestCapability: AudioCapabilityState,
    val hasPrivilegedExtensionRegistered: Boolean,
    val explanation: String
)

/**
 * Packet header for development/testing PCM UDP transport (16 bytes):
 * - magic (2 bytes: 0xCA, 0xB1)
 * - flags (2 bytes: bit0 = dev_mic_only)
 * - sequenceNumber (4 bytes, int)
 * - timestampMs (8 bytes, long)
 */
data class AudioPacket(
    val sequenceNumber: Int,
    val timestampMs: Long,
    val pcmData: ByteArray,
    val isDevMicrophoneAudio: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacket) return false
        return sequenceNumber == other.sequenceNumber &&
            timestampMs == other.timestampMs &&
            isDevMicrophoneAudio == other.isDevMicrophoneAudio &&
            pcmData.contentEquals(other.pcmData)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + pcmData.contentHashCode()
        result = 31 * result + isDevMicrophoneAudio.hashCode()
        return result
    }
}

data class AudioBridgeMetrics(
    val mode: AudioBridgeMode = AudioBridgeMode.IDLE,
    val isRunning: Boolean = false,
    val framesCaptured: Long = 0L,
    val framesPlayed: Long = 0L,
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val packetsLost: Long = 0L,
    val bufferUnderflows: Long = 0L,
    val estimatedLatencyMs: Long = 0L,
    val activeAudioSourceLabel: String = "NONE",
    val lastError: String? = null
)

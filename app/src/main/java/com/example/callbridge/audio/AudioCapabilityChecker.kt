package com.example.callbridge.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat

/**
 * Documented extension point for future privileged / OEM / system-signed / rooted / external
 * hardware bridges that genuinely provide cellular uplink & downlink audio streams.
 *
 * Normal third-party Android apps do NOT hold `android.permission.CAPTURE_AUDIO_OUTPUT` and
 * cannot inject PCM directly into cellular uplink via AudioTrack.
 */
interface PrivilegedCellularAudioExtension {
    val providerName: String
    fun isCellularCaptureVerified(): Boolean
    fun isCellularInjectionVerified(): Boolean
    fun createDownlinkCaptureSource(config: AudioFormatConfig): AudioCaptureSource?
    fun createUplinkPlaybackSink(config: AudioFormatConfig): AudioPlaybackSink?
}

/**
 * Honestly evaluates microphone test readiness versus direct cellular call audio bridging.
 * Never reports [AudioCapabilityState.CELLULAR_AUDIO_SUPPORTED] unless a verified privileged
 * or hardware extension is explicitly installed and confirmed.
 */
class AudioCapabilityChecker(
    context: Context,
    private val formatConfig: AudioFormatConfig = AudioFormatConfig(),
    private val privilegedExtension: PrivilegedCellularAudioExtension? = null
) {
    private val appContext = context.applicationContext

    fun evaluate(): AudioCapabilityReport {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasCaptureOutputPrivilege = ContextCompat.checkSelfPermission(
            appContext,
            "android.permission.CAPTURE_AUDIO_OUTPUT"
        ) == PackageManager.PERMISSION_GRANTED

        val minRecordBuf = runCatching {
            AudioRecord.getMinBufferSize(
                formatConfig.sampleRateHz,
                formatConfig.channelInConfig,
                formatConfig.encoding
            )
        }.getOrDefault(AudioRecord.ERROR_BAD_VALUE)

        val minTrackBuf = runCatching {
            AudioTrack.getMinBufferSize(
                formatConfig.sampleRateHz,
                formatConfig.channelOutConfig,
                formatConfig.encoding
            )
        }.getOrDefault(AudioTrack.ERROR_BAD_VALUE)

        val audioRecordSupported = minRecordBuf > 0
        val audioTrackSupported = minTrackBuf > 0

        val effects = AudioEffectsAvailability(
            acousticEchoCancelerAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
            noiseSuppressorAvailable = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
            automaticGainControlAvailable = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)
        )

        val cellularState = determineCellularAudioCapability(
            hasCaptureOutputPrivilege = hasCaptureOutputPrivilege,
            extension = privilegedExtension
        )

        val micTestState = if (hasMicPerm && audioRecordSupported && audioTrackSupported) {
            AudioCapabilityState.MICROPHONE_TEST_AVAILABLE
        } else {
            AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE
        }

        val explanation = when (cellularState) {
            AudioCapabilityState.CELLULAR_AUDIO_SUPPORTED ->
                "Verified privileged/hardware cellular audio bridge active (${privilegedExtension?.providerName})."
            AudioCapabilityState.CELLULAR_AUDIO_EXPERIMENTAL ->
                "Privileged CAPTURE_AUDIO_OUTPUT detected, but bidirectional cellular audio is unverified."
            AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE,
            AudioCapabilityState.MICROPHONE_TEST_AVAILABLE ->
                "Direct cellular call audio capture (VOICE_CALL/UPLINK/DOWNLINK) and uplink injection are restricted by Android to privileged/system apps. Only local microphone & speaker testing is available."
        }

        return AudioCapabilityReport(
            microphonePermissionGranted = hasMicPerm,
            audioRecordAvailable = audioRecordSupported,
            audioTrackAvailable = audioTrackSupported,
            effectsAvailability = effects,
            cellularAudioCapability = cellularState,
            microphoneTestCapability = micTestState,
            hasPrivilegedExtensionRegistered = privilegedExtension != null,
            explanation = explanation
        )
    }

    companion object {
        fun determineCellularAudioCapability(
            hasCaptureOutputPrivilege: Boolean,
            extension: PrivilegedCellularAudioExtension?
        ): AudioCapabilityState {
            if (extension != null && extension.isCellularCaptureVerified() && extension.isCellularInjectionVerified()) {
                return AudioCapabilityState.CELLULAR_AUDIO_SUPPORTED
            }
            if (hasCaptureOutputPrivilege) {
                return AudioCapabilityState.CELLULAR_AUDIO_EXPERIMENTAL
            }
            return AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE
        }
    }
}

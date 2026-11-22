package com.example.callbridge.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.callbridge.core.Logger

/**
 * Abstraction for PCM 16-bit audio capture sources.
 * Keeps microphone testing cleanly separated from any future privileged cellular capture source.
 */
interface AudioCaptureSource {
    val sourceLabel: String
    val isCellularCallAudio: Boolean
    fun start(): Boolean
    fun readFrame(buffer: ByteArray): Int
    fun stopAndRelease()
}

/**
 * Microphone capture implementation strictly for local microphone testing and dev streaming.
 * Uses VOICE_COMMUNICATION with automatic fallback to MIC.
 * Never constructs AudioRecord when RECORD_AUDIO permission is missing.
 */
class MicrophoneAudioCaptureSource(
    context: Context,
    private val config: AudioFormatConfig = AudioFormatConfig(),
    private val effectsManager: AudioEffectsManager = AudioEffectsManager()
) : AudioCaptureSource {

    private val appContext = context.applicationContext
    private var audioRecord: AudioRecord? = null

    override var sourceLabel: String = "UNINITIALIZED"
        private set

    override val isCellularCallAudio: Boolean = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (audioRecord != null) return true

        val hasPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Logger.w("Audio", "RECORD_AUDIO permission missing; refusing to create AudioRecord")
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            config.channelInConfig,
            config.encoding
        )
        if (minBuffer <= 0) {
            Logger.e("Audio", "AudioRecord unsupported format (minBuffer=$minBuffer)")
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)

        val created = tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufferSize, "VOICE_COMMUNICATION")
            ?: tryCreateAudioRecord(MediaRecorder.AudioSource.MIC, bufferSize, "MIC")

        if (created == null) {
            Logger.e("Audio", "Failed to initialize AudioRecord with VOICE_COMMUNICATION or MIC")
            return false
        }

        return try {
            effectsManager.attachToSession(created.audioSessionId)
            created.startRecording()
            audioRecord = created
            Logger.i("Audio", "Microphone capture started ($sourceLabel, ${config.sampleRateHz}Hz PCM16 Mono)")
            true
        } catch (t: Throwable) {
            Logger.e("Audio", "AudioRecord startRecording failed", t)
            runCatching { created.release() }
            effectsManager.release()
            false
        }
    }

    override fun readFrame(buffer: ByteArray): Int {
        val record = audioRecord ?: return -1
        return try {
            record.read(buffer, 0, buffer.size)
        } catch (t: Throwable) {
            Logger.e("Audio", "AudioRecord read failed", t)
            -1
        }
    }

    override fun stopAndRelease() {
        val record = audioRecord
        audioRecord = null
        effectsManager.release()
        if (record != null) {
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        sourceLabel = "STOPPED"
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateAudioRecord(source: Int, bufferSize: Int, label: String): AudioRecord? {
        return try {
            val candidate = AudioRecord(
                source,
                config.sampleRateHz,
                config.channelInConfig,
                config.encoding,
                bufferSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                sourceLabel = label
                candidate
            } else {
                candidate.release()
                null
            }
        } catch (se: SecurityException) {
            Logger.e("Audio", "SecurityException creating AudioRecord ($label)", se)
            null
        } catch (t: Throwable) {
            Logger.w("Audio", "AudioRecord ($label) failed: ${t.message}")
            null
        }
    }
}

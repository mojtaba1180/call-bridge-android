package com.example.callbridge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.example.callbridge.core.Logger

/**
 * Abstraction for PCM 16-bit audio playback sinks.
 * Standard [LocalAudioTrackSink] plays to local device speaker/earpiece ONLY and does NOT inject
 * audio into an active cellular call uplink.
 */
interface AudioPlaybackSink {
    val isCellularUplinkInjection: Boolean
    fun start(): Boolean
    fun writeFrame(buffer: ByteArray, length: Int): Int
    fun underrunCount(): Long
    fun stopAndRelease()
}

class LocalAudioTrackSink(
    private val config: AudioFormatConfig = AudioFormatConfig()
) : AudioPlaybackSink {

    private var audioTrack: AudioTrack? = null
    private var localUnderflows: Long = 0L

    override val isCellularUplinkInjection: Boolean = false

    @Suppress("DEPRECATION")
    override fun start(): Boolean {
        if (audioTrack != null) return true

        val minBuffer = AudioTrack.getMinBufferSize(
            config.sampleRateHz,
            config.channelOutConfig,
            config.encoding
        )
        if (minBuffer <= 0) {
            Logger.e("Audio", "AudioTrack unsupported format (minBuffer=$minBuffer)")
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)

        val track = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(config.encoding)
                            .setSampleRate(config.sampleRateHz)
                            .setChannelMask(config.channelOutConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    config.sampleRateHz,
                    config.channelOutConfig,
                    config.encoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (t: Throwable) {
            Logger.e("Audio", "Failed to initialize AudioTrack", t)
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            Logger.e("Audio", "AudioTrack failed to enter STATE_INITIALIZED")
            return false
        }

        return try {
            track.play()
            audioTrack = track
            localUnderflows = 0L
            Logger.i("Audio", "Local speaker AudioTrack started (${config.sampleRateHz}Hz PCM16 Mono)")
            true
        } catch (t: Throwable) {
            Logger.e("Audio", "AudioTrack play() failed", t)
            runCatching { track.release() }
            false
        }
    }

    override fun writeFrame(buffer: ByteArray, length: Int): Int {
        val track = audioTrack ?: return -1
        return try {
            val written = track.write(buffer, 0, length)
            if (written < length) {
                localUnderflows++
            }
            written
        } catch (t: Throwable) {
            Logger.e("Audio", "AudioTrack write error", t)
            -1
        }
    }

    override fun underrunCount(): Long {
        val track = audioTrack
        if (track != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return runCatching { track.underrunCount.toLong() }.getOrDefault(localUnderflows)
        }
        return localUnderflows
    }

    override fun stopAndRelease() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.stop()
                }
            }
            runCatching { track.release() }
        }
    }
}

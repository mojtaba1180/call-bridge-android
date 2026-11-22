package com.example.callbridge.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.example.callbridge.core.Logger

/**
 * Safely attaches and releases platform audio effects (AEC, NS, AGC) on an active AudioRecord session.
 */
class AudioEffectsManager {

    private var aec: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    fun attachToSession(audioSessionId: Int): AudioEffectsAvailability {
        release()

        var aecEnabled = false
        var nsEnabled = false
        var agcEnabled = false

        if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
            runCatching {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    aecEnabled = enabled
                }
            }.onFailure { Logger.w("Audio", "AEC init skipped: ${it.message}") }
        }

        if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    nsEnabled = enabled
                }
            }.onFailure { Logger.w("Audio", "NoiseSuppressor init skipped: ${it.message}") }
        }

        if (runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)) {
            runCatching {
                agc = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    agcEnabled = enabled
                }
            }.onFailure { Logger.w("Audio", "AGC init skipped: ${it.message}") }
        }

        Logger.i("Audio", "Audio effects attached (AEC=$aecEnabled, NS=$nsEnabled, AGC=$agcEnabled)")
        return AudioEffectsAvailability(aecEnabled, nsEnabled, agcEnabled)
    }

    fun release() {
        runCatching { aec?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { agc?.release() }
        aec = null
        noiseSuppressor = null
        agc = null
    }
}

package com.example.callbridge.audio

import android.content.Context
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Independent modular audio bridge orchestrator (decoupled from CallManager and BleSignalingServer).
 *
 * Supports:
 * 1. Honest capability reporting distinguishing microphone testing from cellular call audio bridging.
 * 2. Local Microphone-to-Speaker loopback test (16 kHz, 16-bit PCM, Mono) with latency & underrun telemetry.
 * 3. Development-only PCM UDP packet test transport (explicitly non-cellular, non-production).
 */
class LocalAudioBridge(
    context: Context,
    private val formatConfig: AudioFormatConfig = AudioFormatConfig(),
    private val privilegedExtension: PrivilegedCellularAudioExtension? = null
) {
    private val appContext = context.applicationContext
    private val capabilityChecker = AudioCapabilityChecker(appContext, formatConfig, privilegedExtension)

    private val _capabilityReport = MutableStateFlow(capabilityChecker.evaluate())
    val capabilityReport: StateFlow<AudioCapabilityReport> = _capabilityReport.asStateFlow()

    private val _metrics = MutableStateFlow(AudioBridgeMetrics())
    val metrics: StateFlow<AudioBridgeMetrics> = _metrics.asStateFlow()

    private var bridgeScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var receiveJob: Job? = null

    private var captureSource: AudioCaptureSource? = null
    private var playbackSink: AudioPlaybackSink? = null
    private var transport: AudioTransport? = null

    fun refreshCapabilities(): AudioCapabilityReport {
        val report = capabilityChecker.evaluate()
        _capabilityReport.value = report
        if (report.cellularAudioCapability == AudioCapabilityState.CELLULAR_AUDIO_UNAVAILABLE) {
            Logger.d("Audio", "Audio capability evaluated: CELLULAR_AUDIO_UNAVAILABLE (micTest=${report.microphoneTestCapability})")
        }
        return report
    }

    /**
     * Starts a local microphone-to-speaker PCM loopback test (16 kHz Mono) to verify
     * AudioRecord, AudioTrack, AEC/NS/AGC, and round-trip audio buffer latency.
     *
     * Clearly labels the session as [AudioBridgeMode.MICROPHONE_LOOPBACK_TEST], NEVER cellular audio.
     */
    fun startMicrophoneLoopbackTest(): Boolean {
        if (_metrics.value.isRunning) {
            stop()
        }

        val report = refreshCapabilities()
        if (!report.microphonePermissionGranted) {
            _metrics.value = AudioBridgeMetrics(
                mode = AudioBridgeMode.IDLE,
                isRunning = false,
                lastError = "RECORD_AUDIO permission required for microphone test"
            )
            Logger.w("Audio", "Microphone test blocked: RECORD_AUDIO permission missing")
            return false
        }

        val source = MicrophoneAudioCaptureSource(appContext, formatConfig)
        val sink = LocalAudioTrackSink(formatConfig)

        if (!source.start()) {
            source.stopAndRelease()
            _metrics.value = AudioBridgeMetrics(
                mode = AudioBridgeMode.IDLE,
                isRunning = false,
                lastError = "Failed to initialize AudioRecord"
            )
            return false
        }

        if (!sink.start()) {
            source.stopAndRelease()
            sink.stopAndRelease()
            _metrics.value = AudioBridgeMetrics(
                mode = AudioBridgeMode.IDLE,
                isRunning = false,
                lastError = "Failed to initialize AudioTrack"
            )
            return false
        }

        captureSource = source
        playbackSink = sink

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridgeScope = scope

        _metrics.value = AudioBridgeMetrics(
            mode = AudioBridgeMode.MICROPHONE_LOOPBACK_TEST,
            isRunning = true,
            activeAudioSourceLabel = "${source.sourceLabel} (MIC TEST ONLY)"
        )
        Logger.i("Audio", "Started local microphone loopback test (not cellular call audio)")

        captureJob = scope.launch {
            val frameBuffer = ByteArray(formatConfig.frameBytes)
            var captured = 0L
            var played = 0L
            while (isActive) {
                val startNs = System.nanoTime()
                val readBytes = source.readFrame(frameBuffer)
                if (readBytes <= 0) {
                    if (!isActive) break
                    continue
                }
                captured++
                val written = sink.writeFrame(frameBuffer, readBytes)
                if (written > 0) played++
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

                if (captured % 10L == 0L || captured == 1L) {
                    _metrics.value = _metrics.value.copy(
                        framesCaptured = captured,
                        framesPlayed = played,
                        bufferUnderflows = sink.underrunCount(),
                        estimatedLatencyMs = elapsedMs.coerceAtLeast(formatConfig.frameDurationMs.toLong())
                    )
                }
            }
        }
        return true
    }

    /**
     * Starts development/test-only UDP PCM streaming for measuring packet loss and jitter over local Wi-Fi.
     * Explicitly unencrypted test transport; not a cellular audio bridge.
     */
    fun startDevUdpAudioTest(
        localPort: Int = AppConfig.DEFAULT_DEV_AUDIO_UDP_PORT,
        remoteHost: String? = null,
        remotePort: Int = AppConfig.DEFAULT_DEV_AUDIO_UDP_PORT,
        customTransport: AudioTransport = DevUdpPcmAudioTransport()
    ): Boolean {
        if (_metrics.value.isRunning) {
            stop()
        }
        val report = refreshCapabilities()
        if (!report.microphonePermissionGranted) {
            Logger.w("Audio", "DEV UDP audio test blocked: RECORD_AUDIO missing")
            return false
        }

        val source = MicrophoneAudioCaptureSource(appContext, formatConfig)
        val sink = LocalAudioTrackSink(formatConfig)
        if (!customTransport.open(localPort, remoteHost, remotePort) || !source.start() || !sink.start()) {
            source.stopAndRelease()
            sink.stopAndRelease()
            customTransport.close()
            return false
        }

        captureSource = source
        playbackSink = sink
        transport = customTransport

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        bridgeScope = scope

        _metrics.value = AudioBridgeMetrics(
            mode = AudioBridgeMode.MICROPHONE_DEV_UDP_TEST,
            isRunning = true,
            activeAudioSourceLabel = "${source.sourceLabel} (DEV UDP TEST ONLY)"
        )

        captureJob = scope.launch {
            val frameBuffer = ByteArray(formatConfig.frameBytes)
            var seq = 0
            var sent = 0L
            while (isActive) {
                val readBytes = source.readFrame(frameBuffer)
                if (readBytes <= 0) continue
                val payload = if (readBytes == frameBuffer.size) frameBuffer.copyOf() else frameBuffer.copyOf(readBytes)
                val packet = AudioPacket(
                    sequenceNumber = ++seq,
                    timestampMs = System.currentTimeMillis(),
                    pcmData = payload,
                    isDevMicrophoneAudio = true
                )
                if (customTransport.sendPacket(packet)) {
                    sent++
                }
                if (seq % 10 == 0) {
                    _metrics.value = _metrics.value.copy(
                        framesCaptured = seq.toLong(),
                        packetsSent = sent
                    )
                }
            }
        }

        receiveJob = scope.launch {
            var lastSeq = -1
            var received = 0L
            var lost = 0L
            val silenceFrame = ByteArray(formatConfig.frameBytes)
            while (isActive) {
                val packet = customTransport.receivePacket(timeoutMs = formatConfig.frameDurationMs * 3)
                if (packet == null) {
                    // Graceful underflow handling: play silence frame rather than harsh audio glitches
                    sink.writeFrame(silenceFrame, silenceFrame.size)
                    continue
                }
                received++
                if (lastSeq >= 0 && packet.sequenceNumber > lastSeq + 1) {
                    lost += (packet.sequenceNumber - lastSeq - 1)
                }
                lastSeq = packet.sequenceNumber
                sink.writeFrame(packet.pcmData, packet.pcmData.size)
                val latency = (System.currentTimeMillis() - packet.timestampMs).coerceAtLeast(0L)
                _metrics.value = _metrics.value.copy(
                    framesPlayed = received,
                    packetsReceived = received,
                    packetsLost = lost,
                    bufferUnderflows = sink.underrunCount(),
                    estimatedLatencyMs = latency
                )
            }
        }

        Logger.i("Audio", "Started DEV UDP PCM audio test on port $localPort (test mode only)")
        return true
    }

    fun stop() {
        captureJob?.cancel()
        receiveJob?.cancel()
        captureJob = null
        receiveJob = null

        captureSource?.stopAndRelease()
        playbackSink?.stopAndRelease()
        transport?.close()

        captureSource = null
        playbackSink = null
        transport = null

        bridgeScope?.cancel()
        bridgeScope = null

        if (_metrics.value.isRunning) {
            Logger.i("Audio", "Audio test bridge stopped and resources released")
        }
        _metrics.value = AudioBridgeMetrics(mode = AudioBridgeMode.IDLE, isRunning = false)
    }
}

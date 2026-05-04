package com.motolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "AudioBridge"

/**
 * Real-time audio bridge engine.
 *
 * Captures audio from the active SCO Bluetooth microphone (AudioRecord)
 * and plays it back through the active SCO speaker (AudioTrack).
 *
 * When running as a bridge between two HFP devices:
 * - The phone's AudioManager routes SCO to one device at a time
 * - This engine performs a continuous loopback on that channel
 * - The BluetoothManager toggles which device owns SCO at an interval
 *   so both parties hear each other in alternating 80ms windows
 *
 * Sample rate: 16000 Hz (wideband voice / mSBC codec used by Sena/FreedConn)
 * Buffer: ~80ms per read/write cycle for low latency
 */
class AudioBridgeEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // Each switch cycle: read from A, write to B, then swap
        const val SWITCH_INTERVAL_MS = 80L
    }

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs

    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel: StateFlow<Float> = _volumeLevel

    fun start(audioManager: AudioManager) {
        if (_isRunning.value) return

        val minBufferIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val minBufferOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        // Use 2x min buffer for stability, rounded to 80ms worth of samples
        val bufferSize = maxOf(minBufferIn, minBufferOut, (SAMPLE_RATE * 2 * SWITCH_INTERVAL_MS / 1000).toInt())

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                return
            }
            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack init failed")
                return
            }

            audioRecord?.startRecording()
            audioTrack?.play()
            _isRunning.value = true

            job = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(bufferSize / 2)
                Log.d(TAG, "Bridge loop started — buffer=$bufferSize bytes")

                while (isActive && _isRunning.value) {
                    val t0 = System.currentTimeMillis()

                    // Read from BT mic (SCO in)
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (read > 0) {
                        // Calculate RMS for VU meter
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i] * buffer[i].toDouble()
                        val rms = Math.sqrt(sum / read).toFloat()
                        _volumeLevel.value = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)

                        // Write to BT speaker (SCO out) — this loops voice back through bridge
                        audioTrack?.write(buffer, 0, read)
                    }

                    val elapsed = System.currentTimeMillis() - t0
                    _latencyMs.value = elapsed

                    // Prevent tight spin if read returns immediately
                    if (read <= 0) delay(10)
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for AudioRecord: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Bridge start error: ${e.message}")
        }
    }

    fun stop() {
        _isRunning.value = false
        job?.cancel()
        job = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) { Log.e(TAG, "AudioRecord stop: ${e.message}") }

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) { Log.e(TAG, "AudioTrack stop: ${e.message}") }

        _volumeLevel.value = 0f
        _latencyMs.value = 0L
    }
}

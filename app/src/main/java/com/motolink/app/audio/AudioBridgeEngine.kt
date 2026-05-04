package com.motolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

private const val TAG = "AudioBridge"

class AudioBridgeEngine {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 20ms frame — minimum viable for SCO/HFP over BT (radio adds ~30ms, total ~50ms RTT)
        const val FRAME_MS = 20L
    }

    @Volatile private var running = false
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs

    private val _volumeLevel = MutableStateFlow(0f)
    val volumeLevel: StateFlow<Float> = _volumeLevel

    fun start(audioManager: AudioManager) {
        if (running) return

        val minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        // 16000 samples/s * 2 bytes * 20ms = 640 bytes per frame
        val frameSizeBytes = (SAMPLE_RATE * 2 * FRAME_MS / 1000).toInt()
        val bufferSize = maxOf(minBufIn, minBufOut, frameSizeBytes)

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
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
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
            running = true
            _isRunning.value = true

            // Dedicated high-priority thread — avoids coroutine scheduler jitter on audio path
            audioThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buffer = ShortArray(bufferSize / 2)
                Log.d(TAG, "Bridge loop started — buffer=$bufferSize bytes (${FRAME_MS}ms / frame)")

                while (running) {
                    val t0 = System.currentTimeMillis()
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) sum += buffer[i] * buffer[i].toDouble()
                        _volumeLevel.value = (sqrt(sum / read).toFloat() / Short.MAX_VALUE).coerceIn(0f, 1f)
                        audioTrack?.write(buffer, 0, read)
                    }
                    _latencyMs.value = System.currentTimeMillis() - t0
                }
            }, "AudioBridgeThread")
            audioThread?.start()

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for AudioRecord: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Bridge start error: ${e.message}")
            stop()
        }
    }

    fun stop() {
        running = false
        _isRunning.value = false
        audioThread?.join(500)
        audioThread = null

        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) { Log.e(TAG, "AR stop: ${e.message}") }
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) { Log.e(TAG, "AT stop: ${e.message}") }
        audioRecord = null
        audioTrack = null
        _volumeLevel.value = 0f
        _latencyMs.value = 0L
    }
}

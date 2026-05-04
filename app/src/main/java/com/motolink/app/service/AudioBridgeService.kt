package com.motolink.app.service

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.motolink.app.audio.AudioBridgeEngine
import com.motolink.app.bluetooth.MotoLinkBluetoothManager
import com.motolink.app.model.DeviceRole
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class AudioBridgeService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "motolink_bridge"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.motolink.START_BRIDGE"
        const val ACTION_STOP  = "com.motolink.STOP_BRIDGE"
        const val EXTRA_ADDR_A = "addr_a"
        const val EXTRA_ADDR_B = "addr_b"

        // Shared instance so MainActivity can observe state
        var instance: AudioBridgeService? = null
    }

    private lateinit var btManager: MotoLinkBluetoothManager
    private val audioEngine = AudioBridgeEngine()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val wakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoLink::BridgeWake")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        btManager = MotoLinkBluetoothManager(this)
        btManager.initialize()
        createNotificationChannel()

        // Start audio engine when bridge becomes active
        btManager.bridgeActive.onEach { active ->
            if (active) {
                audioEngine.start(audioManager)
                updateNotification("🔴 Puente activo")
            } else {
                audioEngine.stop()
                updateNotification("⏸ Puente detenido")
            }
        }.launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIF_ID, buildNotification("Iniciando..."))

        when (intent?.action) {
            ACTION_START -> {
                val addrA = intent.getStringExtra(EXTRA_ADDR_A)
                val addrB = intent.getStringExtra(EXTRA_ADDR_B)
                handleStart(addrA, addrB)
            }
            ACTION_STOP -> {
                btManager.stopBridge()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun handleStart(addrA: String?, addrB: String?) {
        val paired = btManager.getPairedDevices()
        val deviceA = paired.find { it.address == addrA }
        val deviceB = paired.find { it.address == addrB }

        if (deviceA != null) btManager.assignDevice(deviceA, DeviceRole.DEVICE_A)
        if (deviceB != null) btManager.assignDevice(deviceB, DeviceRole.DEVICE_B)

        if (btManager.startBridge()) {
            if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L) // 4h max
        }
    }

    override fun onDestroy() {
        btManager.stopBridge()
        btManager.destroy()
        audioEngine.stop()
        if (wakeLock.isHeld) wakeLock.release()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // Expose managers to Activity
    fun getBluetoothManager() = btManager
    fun getAudioEngine() = audioEngine

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MotoLink Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Puente de voz activo entre intercomunicadores" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioBridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoLink")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status))
    }
}

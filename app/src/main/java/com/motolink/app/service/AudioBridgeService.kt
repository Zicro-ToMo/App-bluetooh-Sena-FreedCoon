package com.motolink.app.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.*
import android.util.Log
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

        var instance: AudioBridgeService? = null
    }

    private lateinit var btManager: MotoLinkBluetoothManager
    private val audioEngine = AudioBridgeEngine()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val wakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoLink::BridgeWake")
    }

    // Persist device addresses so onTaskRemoved can restart with correct devices
    private var lastAddrA: String? = null
    private var lastAddrB: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        btManager = MotoLinkBluetoothManager(this)
        btManager.initialize()
        createNotificationChannel()

        btManager.bridgeActive.onEach { active ->
            if (active) {
                audioEngine.start(audioManager)
                updateNotification("Puente activo")
            } else {
                audioEngine.stop()
                updateNotification("Puente detenido")
            }
        }.launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = buildNotification("Iniciando...")
        // Specify foreground service type for Android 10+ — required for microphone access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> {
                val addrA = intent.getStringExtra(EXTRA_ADDR_A)
                val addrB = intent.getStringExtra(EXTRA_ADDR_B)
                lastAddrA = addrA
                lastAddrB = addrB
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

    // Called when user swipes app from recents — Samsung One UI may kill the process.
    // Schedule a restart via AlarmManager so the bridge recovers automatically.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (lastAddrA == null && lastAddrB == null) return

        val restartIntent = Intent(applicationContext, AudioBridgeService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_ADDR_A, lastAddrA)
            putExtra(EXTRA_ADDR_B, lastAddrB)
        }
        val pending = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000L, pending)
        Log.d("MotoLinkService", "Service task removed — restart scheduled in 2s")
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

    fun getBluetoothManager() = btManager
    fun getAudioEngine() = audioEngine

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        // IMPORTANCE_DEFAULT keeps One UI from collapsing/dismissing the notification;
        // sound is disabled on the channel so it's silent despite the higher importance.
        val channel = NotificationChannel(
            CHANNEL_ID, "MotoLink Bridge",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Puente de voz activo entre intercomunicadores"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioBridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MotoLink")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status))
    }
}


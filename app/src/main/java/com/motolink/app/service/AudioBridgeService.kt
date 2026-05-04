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
import com.motolink.app.bluetooth.DiagnosticScanner
import com.motolink.app.bluetooth.MotoLinkBluetoothManager
import com.motolink.app.model.ConnectionState
import com.motolink.app.model.DeviceRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class AudioBridgeService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "motolink_bridge"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.motolink.START_BRIDGE"
        const val ACTION_STOP  = "com.motolink.STOP_BRIDGE"
        const val EXTRA_ADDR_A = "addr_a"
        const val EXTRA_ADDR_B = "addr_b"

        private const val TAG = "MotoLinkService"

        var instance: AudioBridgeService? = null
    }

    private lateinit var btManager: MotoLinkBluetoothManager
    private val audioEngine = AudioBridgeEngine()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val wakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoLink::BridgeWake")
    }

    private var lastAddrA: String? = null
    private var lastAddrB: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        btManager = MotoLinkBluetoothManager(this)
        btManager.initialize()
        createNotificationChannel()

        // FIX C: Do not start the engine the instant bridgeActive becomes true.
        // SCO is not yet established at that moment — starting immediately causes
        // AudioRecord to capture from the phone mic and AudioTrack to output on
        // the phone speaker. Use waitForScoThenStartEngine() to defer until SCO_ACTIVE.
        btManager.bridgeActive.onEach { active ->
            if (active) {
                waitForScoThenStartEngine()
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
            if (!wakeLock.isHeld) wakeLock.acquire(4 * 60 * 60 * 1000L)
        }
    }

    // FIX C: Poll every 300ms until at least one device reaches SCO_ACTIVE state,
    // confirming that the SCO audio channel is established and routing is correct.
    // Maximum wait: 15 × 300ms = 4.5 seconds. Falls through to start anyway so the
    // bridge doesn't silently fail if the SCO state notification is delayed.
    private fun waitForScoThenStartEngine() {
        lifecycleScope.launch {
            val maxAttempts = 15
            for (attempt in 1..maxAttempts) {
                if (!btManager.bridgeActive.value) return@launch  // bridge was stopped

                val aActive = btManager.deviceA.value?.state == ConnectionState.SCO_ACTIVE
                val bActive = btManager.deviceB.value?.state == ConnectionState.SCO_ACTIVE
                if (aActive || bActive) {
                    Log.d(TAG, "SCO confirmed active after ${attempt * 300}ms — starting audio engine")
                    audioEngine.start(audioManager)
                    return@launch
                }
                delay(300L)
            }
            // Fallback: SCO state didn't update within timeout (e.g. old firmware).
            // Start anyway so the bridge isn't silently broken.
            if (btManager.bridgeActive.value) {
                Log.w(TAG, "SCO wait timed out (${maxAttempts * 300}ms) — starting engine as fallback")
                audioEngine.start(audioManager)
            }
        }
    }

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
        Log.d(TAG, "Service task removed — restart scheduled in 2s")
    }

    override fun onDestroy() {
        btManager.stopBridge()
        btManager.destroy()
        // FIX C: release() explicitly frees native AudioRecord/AudioTrack hardware resources
        audioEngine.release()
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

    fun endSession() {
        btManager.stopBridge()
        updateNotification("Listo para reiniciar")
    }

    fun getDiagnosticScanner() = DiagnosticScanner(btManager.getHeadsetProxy())

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
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

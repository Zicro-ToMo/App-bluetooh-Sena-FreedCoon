package com.motolink.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.motolink.app.model.BtDeviceInfo
import com.motolink.app.model.ConnectionState
import com.motolink.app.model.DeviceRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "MotoLinkBT"
private const val MAX_RECONNECT_ATTEMPTS = 3
private const val MAX_SCO_RECONNECT = 5

@SuppressLint("MissingPermission")
class MotoLinkBluetoothManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var headsetProxy: BluetoothHeadset? = null
    private var activeScoIndex = 0

    private val _deviceA = MutableStateFlow<BtDeviceInfo?>(null)
    val deviceA: StateFlow<BtDeviceInfo?> = _deviceA

    private val _deviceB = MutableStateFlow<BtDeviceInfo?>(null)
    val deviceB: StateFlow<BtDeviceInfo?> = _deviceB

    private val _bridgeActive = MutableStateFlow(false)
    val bridgeActive: StateFlow<Boolean> = _bridgeActive

    private val _statusMessage = MutableStateFlow("Listo para conectar")
    val statusMessage: StateFlow<String> = _statusMessage

    // HFP device-level reconnect (handles device physically dropping)
    private val reconnectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    // SCO channel-level reconnect (handles SCO dropout without full BT disconnect)
    private var scoReconnectJob: Job? = null
    private var scoReconnectAttempts = 0

    // ── HFP Profile Listener ──────────────────────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                Log.d(TAG, "HFP proxy connected")
                _statusMessage.value = "Perfil HFP listo"
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                Log.d(TAG, "HFP proxy disconnected")
            }
        }
    }

    // ── BroadcastReceivers ────────────────────────────────────────────────────

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    handleHeadsetConnectionChange(device, state)
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    handleAudioStateChange(device, state)
                }
            }
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.d(TAG, "SCO state broadcast: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    scoReconnectAttempts = 0
                    scoReconnectJob?.cancel()
                    // FIX A: Apply BT-only routing HERE, after SCO is confirmed.
                    // Applying it in startBridge() is a race condition — Android can
                    // override isSpeakerphoneOn during SCO negotiation, causing fallback
                    // to the phone speaker.
                    applyBtOnlyRouting()
                    _statusMessage.value = "SCO activo — Puente de voz en línea"
                    updateActiveDeviceState(ConnectionState.SCO_ACTIVE)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    _statusMessage.value = "SCO desconectado"
                    updateActiveDeviceState(ConnectionState.CONNECTED)
                    // Auto-retry SCO if the bridge should still be active
                    if (_bridgeActive.value) scheduleScoReconnect()
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    _statusMessage.value = "Error en SCO"
                    updateActiveDeviceState(ConnectionState.ERROR)
                }
            }
        }
    }

    // ── Init & Teardown ───────────────────────────────────────────────────────

    fun initialize() {
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)

        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
        context.registerReceiver(headsetReceiver, filter)
        context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }

    fun destroy() {
        reconnectJob?.cancel()
        scoReconnectJob?.cancel()
        reconnectScope.cancel()
        stopBridge()
        try {
            context.unregisterReceiver(headsetReceiver)
            context.unregisterReceiver(scoReceiver)
        } catch (e: Exception) { /* already unregistered */ }
        headsetProxy?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
    }

    // ── Device Assignment ─────────────────────────────────────────────────────

    fun assignDevice(device: BluetoothDevice, role: DeviceRole) {
        val info = BtDeviceInfo(device, role)
        when (role) {
            DeviceRole.DEVICE_A -> _deviceA.value = info
            DeviceRole.DEVICE_B -> _deviceB.value = info
        }
        _statusMessage.value = "${info.displayName} asignado como ${if (role == DeviceRole.DEVICE_A) "Dispositivo A" else "Dispositivo B"}"
    }

    fun clearDevice(role: DeviceRole) {
        when (role) {
            DeviceRole.DEVICE_A -> _deviceA.value = null
            DeviceRole.DEVICE_B -> _deviceB.value = null
        }
    }

    // ── Bridge Control ────────────────────────────────────────────────────────

    fun startBridge(): Boolean {
        val a = _deviceA.value
        val b = _deviceB.value

        if (a == null || b == null) {
            _statusMessage.value = "Necesitas asignar ambos dispositivos primero"
            return false
        }

        reconnectAttempts = 0
        scoReconnectAttempts = 0

        // FIX A: Only set MODE and kick off SCO negotiation here.
        // Do NOT set isSpeakerphoneOn or isBluetoothScoOn yet — SCO is not established
        // at this point. The full routing is applied in applyBtOnlyRouting() once
        // SCO_AUDIO_STATE_CONNECTED confirms the channel is ready.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        _deviceA.value = a.copy(state = ConnectionState.CONNECTING)
        _deviceB.value = b.copy(state = ConnectionState.CONNECTING)
        _bridgeActive.value = true
        _statusMessage.value = "Iniciando puente de audio..."

        return true
    }

    fun stopBridge() {
        reconnectJob?.cancel()
        scoReconnectJob?.cancel()
        _bridgeActive.value = false
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        audioManager.mode = AudioManager.MODE_NORMAL

        _deviceA.value = _deviceA.value?.copy(state = ConnectionState.CONNECTED)
        _deviceB.value = _deviceB.value?.copy(state = ConnectionState.CONNECTED)
        _statusMessage.value = "Puente detenido"
    }

    // ── Paired Devices ────────────────────────────────────────────────────────

    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun isBluetoothEnabled() = bluetoothAdapter?.isEnabled == true

    fun getHeadsetProxy(): BluetoothHeadset? = headsetProxy

    // ── Internal ──────────────────────────────────────────────────────────────

    // Called ONLY from SCO_AUDIO_STATE_CONNECTED. Applying routing before SCO is
    // established lets Android's audio policy override it during negotiation,
    // routing audio to the phone speaker instead of the BT channel.
    private fun applyBtOnlyRouting() {
        Log.d(TAG, "SCO CONNECTED — applying audio routing fixes")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    // Retries SCO negotiation up to MAX_SCO_RECONNECT times with 1500ms between attempts.
    // Triggered when SCO drops while bridgeActive is still true.
    private fun scheduleScoReconnect() {
        if (scoReconnectAttempts >= MAX_SCO_RECONNECT) {
            Log.w(TAG, "SCO reconnect exhausted after $MAX_SCO_RECONNECT attempts — stopping bridge")
            _statusMessage.value = "No se pudo reconectar SCO. Reinicia el puente."
            stopBridge()
            return
        }
        scoReconnectAttempts++
        scoReconnectJob?.cancel()
        scoReconnectJob = reconnectScope.launch {
            Log.d(TAG, "SCO reconnect attempt $scoReconnectAttempts/$MAX_SCO_RECONNECT — waiting 1500ms")
            _statusMessage.value = "Reconectando SCO ($scoReconnectAttempts/$MAX_SCO_RECONNECT)..."
            delay(1500L)
            if (!isActive || !_bridgeActive.value) return@launch
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
        }
    }

    private fun handleHeadsetConnectionChange(device: BluetoothDevice?, state: Int) {
        device ?: return
        val newState = when (state) {
            BluetoothProfile.STATE_CONNECTED -> ConnectionState.CONNECTED
            BluetoothProfile.STATE_CONNECTING -> ConnectionState.CONNECTING
            BluetoothProfile.STATE_DISCONNECTED -> ConnectionState.DISCONNECTED
            else -> ConnectionState.ERROR
        }
        Log.d(TAG, "Headset state change: ${device.name} → $newState")

        val isDeviceA = _deviceA.value?.device?.address == device.address
        val isDeviceB = _deviceB.value?.device?.address == device.address

        if (isDeviceA) _deviceA.value = _deviceA.value?.copy(state = newState)
        if (isDeviceB) _deviceB.value = _deviceB.value?.copy(state = newState)

        if (newState == ConnectionState.DISCONNECTED && _bridgeActive.value && (isDeviceA || isDeviceB)) {
            val name = if (isDeviceA) _deviceA.value?.displayName else _deviceB.value?.displayName
            Log.d(TAG, "Bridge device dropped: $name — scheduling HFP reconnect")
            _statusMessage.value = "${name ?: device.address} desconectado — reconectando..."
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            _statusMessage.value = "No se pudo reconectar tras $MAX_RECONNECT_ATTEMPTS intentos. Reinicia el puente."
            stopBridge()
            return
        }

        reconnectJob?.cancel()
        reconnectJob = reconnectScope.launch {
            val delayMs = reconnectAttempts * 2000L
            Log.d(TAG, "HFP reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${delayMs}ms")
            _statusMessage.value = "Reconectando (intento $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)..."
            delay(delayMs)

            if (!isActive || !_bridgeActive.value) return@launch

            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            delay(500)
            if (!isActive || !_bridgeActive.value) return@launch
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            _statusMessage.value = "SCO reiniciado — esperando dispositivo..."
        }
    }

    private fun handleAudioStateChange(device: BluetoothDevice?, state: Int) {
        device ?: return
        Log.d(TAG, "Audio state: ${device.name} → $state")
    }

    private fun updateActiveDeviceState(state: ConnectionState) {
        if (activeScoIndex == 0)
            _deviceA.value = _deviceA.value?.copy(state = state)
        else
            _deviceB.value = _deviceB.value?.copy(state = state)
    }
}

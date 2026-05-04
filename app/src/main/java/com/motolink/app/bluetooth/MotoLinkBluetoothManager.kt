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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "MotoLinkBT"

@SuppressLint("MissingPermission")
class MotoLinkBluetoothManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // HFP proxy for voice calls
    private var headsetProxy: BluetoothHeadset? = null

    // Currently active SCO device index (0 = A, 1 = B)
    private var activeScoIndex = 0

    private val _deviceA = MutableStateFlow<BtDeviceInfo?>(null)
    val deviceA: StateFlow<BtDeviceInfo?> = _deviceA

    private val _deviceB = MutableStateFlow<BtDeviceInfo?>(null)
    val deviceB: StateFlow<BtDeviceInfo?> = _deviceB

    private val _bridgeActive = MutableStateFlow(false)
    val bridgeActive: StateFlow<Boolean> = _bridgeActive

    private val _statusMessage = MutableStateFlow("Listo para conectar")
    val statusMessage: StateFlow<String> = _statusMessage

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
            Log.d(TAG, "SCO state: $state")
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    _statusMessage.value = "SCO activo — Puente de voz en línea"
                    updateActiveDeviceState(ConnectionState.SCO_ACTIVE)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    _statusMessage.value = "SCO desconectado"
                    updateActiveDeviceState(ConnectionState.CONNECTED)
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

    /**
     * Starts the audio bridge between Device A and Device B.
     *
     * Strategy:
     * Both devices connect via HFP. Android SCO only supports ONE active
     * SCO channel at a time at the OS level. We use a fast-switching approach:
     * - Device A opens SCO → audio captured via AudioRecord → sent to AudioTrack on SCO
     * - Every SWITCH_INTERVAL_MS we toggle SCO to Device B to pick up audio from B → route to A
     *
     * This creates a half-duplex style bridge. For true full-duplex the
     * audio engine in AudioBridgeService handles continuous loopback on
     * whichever SCO is currently active.
     */
    fun startBridge(): Boolean {
        val a = _deviceA.value
        val b = _deviceB.value

        if (a == null || b == null) {
            _statusMessage.value = "Necesitas asignar ambos dispositivos primero"
            return false
        }

        // Configure AudioManager for SCO voice
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        // Start SCO — connects to both paired HFP devices
        // Android will route SCO to the "active" HFP device
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        _deviceA.value = a.copy(state = ConnectionState.CONNECTING)
        _deviceB.value = b.copy(state = ConnectionState.CONNECTING)
        _bridgeActive.value = true
        _statusMessage.value = "Iniciando puente de audio..."

        return true
    }

    fun stopBridge() {
        _bridgeActive.value = false
        audioManager.isBluetoothScoOn = false
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

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun handleHeadsetConnectionChange(device: BluetoothDevice?, state: Int) {
        device ?: return
        val newState = when (state) {
            BluetoothProfile.STATE_CONNECTED -> ConnectionState.CONNECTED
            BluetoothProfile.STATE_CONNECTING -> ConnectionState.CONNECTING
            BluetoothProfile.STATE_DISCONNECTED -> ConnectionState.DISCONNECTED
            else -> ConnectionState.ERROR
        }
        Log.d(TAG, "Headset state change: ${device.name} → $newState")

        if (_deviceA.value?.device?.address == device.address)
            _deviceA.value = _deviceA.value?.copy(state = newState)
        if (_deviceB.value?.device?.address == device.address)
            _deviceB.value = _deviceB.value?.copy(state = newState)
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

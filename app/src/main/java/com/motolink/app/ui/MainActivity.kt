package com.motolink.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motolink.app.R
import com.motolink.app.databinding.ActivityMainBinding
import com.motolink.app.model.BtDeviceInfo
import com.motolink.app.model.ConnectionState
import com.motolink.app.model.DeviceRole
import com.motolink.app.service.AudioBridgeService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bridgeService: AudioBridgeService? = null
    private var isBound = false

    private var selectedDeviceA: BluetoothDevice? = null
    private var selectedDeviceB: BluetoothDevice? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            onPermissionsGranted()
        } else {
            showToast("Se requieren permisos de Bluetooth y micrófono")
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (bluetoothAdapter?.isEnabled == true) onPermissionsGranted() }

    // ── Service Connection ────────────────────────────────────────────────────

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBound = true
            bridgeService = AudioBridgeService.instance
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bridgeService = null
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Bind to service if already running
        val intent = Intent(this, AudioBridgeService::class.java)
        bindService(intent, serviceConnection, 0) // don't auto-create
    }

    override fun onPause() {
        super.onPause()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupUI() {
        // Device A selector
        binding.cardDeviceA.setOnClickListener {
            showDevicePicker(DeviceRole.DEVICE_A)
        }
        binding.btnClearA.setOnClickListener {
            selectedDeviceA = null
            updateDeviceCard(DeviceRole.DEVICE_A, null)
        }

        // Device B selector
        binding.cardDeviceB.setOnClickListener {
            showDevicePicker(DeviceRole.DEVICE_B)
        }
        binding.btnClearB.setOnClickListener {
            selectedDeviceB = null
            updateDeviceCard(DeviceRole.DEVICE_B, null)
        }

        // Bridge toggle
        binding.btnBridge.setOnClickListener {
            if (bridgeService?.getBluetoothManager()?.bridgeActive?.value == true) {
                stopBridge()
            } else {
                startBridge()
            }
        }

        // Refresh paired devices
        binding.btnRefresh.setOnClickListener {
            loadPairedDevices()
        }
    }

    private fun observeServiceState() {
        val svc = bridgeService ?: return
        val btMgr = svc.getBluetoothManager()
        val audioEngine = svc.getAudioEngine()

        lifecycleScope.launch {
            btMgr.deviceA.collect { info ->
                info?.let { updateDeviceCardFromInfo(DeviceRole.DEVICE_A, it) }
            }
        }
        lifecycleScope.launch {
            btMgr.deviceB.collect { info ->
                info?.let { updateDeviceCardFromInfo(DeviceRole.DEVICE_B, it) }
            }
        }
        lifecycleScope.launch {
            btMgr.bridgeActive.collect { active ->
                updateBridgeButton(active)
                binding.vuMeterContainer.visibility = if (active) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            btMgr.statusMessage.collect { msg ->
                binding.tvStatus.text = msg
            }
        }
        lifecycleScope.launch {
            audioEngine.volumeLevel.collect { level ->
                binding.vuMeter.progress = (level * 100).toInt()
            }
        }
        lifecycleScope.launch {
            audioEngine.latencyMs.collect { ms ->
                binding.tvLatency.text = "Latencia: ${ms}ms"
            }
        }
    }

    // ── Bridge Control ────────────────────────────────────────────────────────

    private fun startBridge() {
        val addrA = selectedDeviceA?.address
        val addrB = selectedDeviceB?.address

        if (addrA == null || addrB == null) {
            showToast("Selecciona ambos dispositivos primero")
            return
        }
        if (addrA == addrB) {
            showToast("Selecciona dos dispositivos diferentes")
            return
        }

        val intent = Intent(this, AudioBridgeService::class.java).apply {
            action = AudioBridgeService.ACTION_START
            putExtra(AudioBridgeService.EXTRA_ADDR_A, addrA)
            putExtra(AudioBridgeService.EXTRA_ADDR_B, addrB)
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopBridge() {
        val intent = Intent(this, AudioBridgeService::class.java).apply {
            action = AudioBridgeService.ACTION_STOP
        }
        startService(intent)
    }

    // ── Device Picker ─────────────────────────────────────────────────────────

    private fun showDevicePicker(role: DeviceRole) {
        if (pairedDevices.isEmpty()) {
            showToast("No hay dispositivos Bluetooth emparejados")
            return
        }

        val names = pairedDevices.map { it.name ?: it.address }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (role == DeviceRole.DEVICE_A) "Seleccionar Dispositivo A (FreedConn)" else "Seleccionar Dispositivo B (Sena 10)")
            .setItems(names) { _, idx ->
                val device = pairedDevices[idx]
                if (role == DeviceRole.DEVICE_A) {
                    selectedDeviceA = device
                } else {
                    selectedDeviceB = device
                }
                updateDeviceCard(role, device)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── UI Updates ────────────────────────────────────────────────────────────

    private fun updateDeviceCard(role: DeviceRole, device: BluetoothDevice?) {
        if (role == DeviceRole.DEVICE_A) {
            binding.tvDeviceAName.text = device?.name ?: "Toca para seleccionar"
            binding.tvDeviceAAddr.text = device?.address ?: ""
            binding.btnClearA.visibility = if (device != null) View.VISIBLE else View.GONE
            binding.tvDeviceAStatus.text = if (device != null) "Emparejado" else ""
        } else {
            binding.tvDeviceBName.text = device?.name ?: "Toca para seleccionar"
            binding.tvDeviceBAddr.text = device?.address ?: ""
            binding.btnClearB.visibility = if (device != null) View.VISIBLE else View.GONE
            binding.tvDeviceBStatus.text = if (device != null) "Emparejado" else ""
        }
    }

    private fun updateDeviceCardFromInfo(role: DeviceRole, info: BtDeviceInfo) {
        val stateText = when (info.state) {
            ConnectionState.DISCONNECTED -> "Desconectado"
            ConnectionState.CONNECTING   -> "Conectando..."
            ConnectionState.CONNECTED    -> "Conectado"
            ConnectionState.SCO_ACTIVE   -> "🎙 Voz activa"
            ConnectionState.ERROR        -> "⚠ Error"
        }
        if (role == DeviceRole.DEVICE_A) {
            binding.tvDeviceAName.text = info.displayName
            binding.tvDeviceAAddr.text = info.device.address
            binding.tvDeviceAStatus.text = stateText
        } else {
            binding.tvDeviceBName.text = info.displayName
            binding.tvDeviceBAddr.text = info.device.address
            binding.tvDeviceBStatus.text = stateText
        }
    }

    private fun updateBridgeButton(active: Boolean) {
        binding.btnBridge.text = if (active) "⏹ Detener Puente" else "▶ Iniciar Puente"
        binding.btnBridge.setBackgroundColor(
            ContextCompat.getColor(this, if (active) R.color.stop_red else R.color.start_green)
        )
        binding.indicatorBridge.setBackgroundColor(
            ContextCompat.getColor(this, if (active) R.color.start_green else R.color.indicator_off)
        )
    }

    // ── Permissions & BT Init ─────────────────────────────────────────────────

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsGranted() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        loadPairedDevices()
    }

    private fun loadPairedDevices() {
        pairedDevices = try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        binding.tvPairedCount.text = "${pairedDevices.size} dispositivo(s) emparejado(s)"
        if (pairedDevices.isEmpty()) {
            binding.tvStatus.text = "Ve a Ajustes → Bluetooth y empareja FreedConn y Sena 10 primero"
        }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

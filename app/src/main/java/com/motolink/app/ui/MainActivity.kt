package com.motolink.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motolink.app.R
import com.motolink.app.databinding.ActivityMainBinding
import com.motolink.app.bluetooth.DiagnosticResult
import com.motolink.app.bluetooth.DiagnosticScanner
import com.motolink.app.bluetooth.DiagnosticStatus
import com.motolink.app.model.BtDeviceInfo
import com.motolink.app.model.ConnectionState
import com.motolink.app.model.DeviceRole
import com.motolink.app.service.AudioBridgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bridgeService: AudioBridgeService? = null
    private var isBound = false

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

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
        val denied = results.filterValues { !it }.keys
        when {
            denied.isEmpty() -> onPermissionsGranted()
            // Permanently denied — shouldShowRationale returns false after the user checked "Never ask again"
            denied.any { !shouldShowRequestPermissionRationale(it) } -> showPermissionSettingsDialog()
            else -> showPermissionRationaleDialog()
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
        val intent = Intent(this, AudioBridgeService::class.java)
        bindService(intent, serviceConnection, 0)
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
        binding.cardDeviceA.setOnClickListener { showDevicePicker(DeviceRole.DEVICE_A) }
        binding.btnClearA.setOnClickListener {
            selectedDeviceA = null
            updateDeviceCard(DeviceRole.DEVICE_A, null)
        }

        binding.cardDeviceB.setOnClickListener { showDevicePicker(DeviceRole.DEVICE_B) }
        binding.btnClearB.setOnClickListener {
            selectedDeviceB = null
            updateDeviceCard(DeviceRole.DEVICE_B, null)
        }

        binding.btnBridge.setOnClickListener {
            if (bridgeService?.getBluetoothManager()?.bridgeActive?.value == true) {
                stopBridge()
            } else {
                startBridge()
            }
        }

        binding.btnEndCall.setOnClickListener { endConversation() }

        binding.btnDiagnose.setOnClickListener { runDiagnostic() }

        binding.btnRefresh.setOnClickListener { loadPairedDevices() }

        setupVolumeControls()
    }

    private fun observeServiceState() {
        val svc = bridgeService ?: return
        val btMgr = svc.getBluetoothManager()
        val audioEngine = svc.getAudioEngine()

        lifecycleScope.launch {
            btMgr.deviceA.collect { info -> info?.let { updateDeviceCardFromInfo(DeviceRole.DEVICE_A, it) } }
        }
        lifecycleScope.launch {
            btMgr.deviceB.collect { info -> info?.let { updateDeviceCardFromInfo(DeviceRole.DEVICE_B, it) } }
        }
        lifecycleScope.launch {
            btMgr.bridgeActive.collect { active ->
                updateBridgeButton(active)
                binding.vuMeterContainer.visibility = if (active) View.VISIBLE else View.GONE
                if (active) initVolumeSliders()
            }
        }
        lifecycleScope.launch {
            btMgr.statusMessage.collect { msg -> binding.tvStatus.text = msg }
        }
        lifecycleScope.launch {
            audioEngine.volumeLevel.collect { level -> binding.vuMeter.progress = (level * 100).toInt() }
        }
        lifecycleScope.launch {
            audioEngine.latencyMs.collect { ms -> binding.tvLatency.text = "Latencia: ${ms}ms" }
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

    // Cuts the active voice session but leaves the service and BT connections alive
    // so the user can restart the bridge without a full service restart cycle.
    private fun endConversation() {
        bridgeService?.endSession()
        showToast("Conversación finalizada")
    }

    // ── Device Picker ─────────────────────────────────────────────────────────

    private fun showDevicePicker(role: DeviceRole) {
        if (pairedDevices.isEmpty()) {
            showToast("No hay dispositivos Bluetooth emparejados")
            return
        }

        val names = pairedDevices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (role == DeviceRole.DEVICE_A) "Seleccionar Dispositivo A (FreedConn)" else "Seleccionar Dispositivo B (Sena 10)")
            .setItems(names) { _, idx ->
                val device = pairedDevices[idx]
                if (role == DeviceRole.DEVICE_A) selectedDeviceA = device else selectedDeviceB = device
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
            ConnectionState.SCO_ACTIVE   -> "Voz activa"
            ConnectionState.ERROR        -> "Error"
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
        binding.btnBridge.text = if (active) "Detener Puente" else "Iniciar Puente"
        binding.btnBridge.setBackgroundColor(
            ContextCompat.getColor(this, if (active) R.color.stop_red else R.color.start_green)
        )
        binding.indicatorBridge.setBackgroundColor(
            ContextCompat.getColor(this, if (active) R.color.start_green else R.color.indicator_off)
        )
        binding.btnEndCall.visibility = if (active) View.VISIBLE else View.GONE
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkPermissions() {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) onPermissionsGranted()
        else permissionLauncher.launch(missing)
    }

    private fun getMissingPermissions(): Array<String> =
        requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    private fun onPermissionsGranted() {
        if (bluetoothAdapter?.isEnabled == false) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        loadPairedDevices()
        requestBatteryOptimizationExemption()
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permisos requeridos")
            .setMessage("MotoLink necesita acceso al micrófono y Bluetooth para crear el puente de audio entre los intercomunicadores FreedConn y Sena 10.")
            .setPositiveButton("Conceder permisos") { _, _ -> permissionLauncher.launch(getMissingPermissions()) }
            .setNegativeButton("Cancelar") { _, _ -> showPermissionDeniedUI() }
            .show()
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permisos bloqueados")
            .setMessage("Los permisos están bloqueados permanentemente. Ve a Configuración → Aplicaciones → MotoLink → Permisos para habilitarlos.")
            .setPositiveButton("Abrir Configuración") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> showPermissionDeniedUI() }
            .show()
    }

    private fun showPermissionDeniedUI() {
        binding.tvStatus.text = "Se requieren permisos de micrófono y Bluetooth para usar MotoLink"
        binding.btnBridge.isEnabled = false
        binding.cardDeviceA.isEnabled = false
        binding.cardDeviceB.isEnabled = false
    }

    // Ask user to exclude MotoLink from battery optimization so Samsung One UI
    // doesn't kill the service during an active bridge session.
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            // Device doesn't support this intent — ignore silently
        }
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

    // ── Diagnostic Scanner ────────────────────────────────────────────────────

    private fun runDiagnostic() {
        val devA = selectedDeviceA
        val devB = selectedDeviceB
        if (devA == null && devB == null) {
            showToast("Selecciona al menos un dispositivo primero")
            return
        }

        binding.btnDiagnose.isEnabled = false
        binding.btnDiagnose.text = "Analizando..."

        lifecycleScope.launch {
            val scanner = bridgeService?.getDiagnosticScanner() ?: DiagnosticScanner(null)
            val resultA = devA?.let { withContext(Dispatchers.Default) { scanner.scanDevice(it) } }
            val resultB = devB?.let { withContext(Dispatchers.Default) { scanner.scanDevice(it) } }

            binding.btnDiagnose.isEnabled = true
            binding.btnDiagnose.text = "🔍 Verificar Dispositivos"
            showDiagnosticDialog(devA, resultA, devB, resultB)
        }
    }

    private fun showDiagnosticDialog(
        devA: BluetoothDevice?, resultA: DiagnosticResult?,
        devB: BluetoothDevice?, resultB: DiagnosticResult?
    ) {
        fun icon(s: DiagnosticStatus) = when (s) {
            DiagnosticStatus.BUENO       -> "✅"
            DiagnosticStatus.ADVERTENCIA -> "⚠️"
            DiagnosticStatus.ERROR       -> "❌"
        }

        val sb = StringBuilder()
        resultA?.let { r ->
            sb.append("[Dispositivo A] ${devA?.name ?: devA?.address}\n")
            sb.append("${icon(r.status)} ${r.status}  —  ${r.mensaje}\n")
            sb.append("Sugerencia: ${r.sugerencia}\n")
        }
        if (resultA != null && resultB != null) sb.append("\n")
        resultB?.let { r ->
            sb.append("[Dispositivo B] ${devB?.name ?: devB?.address}\n")
            sb.append("${icon(r.status)} ${r.status}  —  ${r.mensaje}\n")
            sb.append("Sugerencia: ${r.sugerencia}")
        }

        val hasErrors = resultA?.status == DiagnosticStatus.ERROR ||
                        resultB?.status == DiagnosticStatus.ERROR

        AlertDialog.Builder(this)
            .setTitle("Diagnóstico de Dispositivos")
            .setMessage(sb.toString())
            .setPositiveButton("Cerrar", null)
            .apply {
                if (hasErrors) {
                    setNeutralButton("Intentar reconectar") { _, _ ->
                        tryReconnect(devA, resultA, devB, resultB)
                    }
                }
            }
            .show()
    }

    @Suppress("MissingPermission")
    private fun tryReconnect(
        devA: BluetoothDevice?, resultA: DiagnosticResult?,
        devB: BluetoothDevice?, resultB: DiagnosticResult?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (resultA?.status == DiagnosticStatus.ERROR && devA != null) devA.createBond()
                if (resultB?.status == DiagnosticStatus.ERROR && devB != null) devB.createBond()
            } catch (_: Exception) { }
        }
        showToast("Intentando reconectar...")
    }

    // ── Volume Controls ───────────────────────────────────────────────────────

    private fun setupVolumeControls() {
        binding.seekbarMusicVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                binding.tvMusicVolPct.text = "${progress * 100 / max}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.seekbarVoiceVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, progress, 0)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
                binding.tvVoiceVolPct.text = "${progress * 100 / max}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // Sync slider positions with current system volume when bridge becomes active.
    private fun initVolumeSliders() {
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.seekbarMusicVolume.max = maxMusic
        binding.seekbarMusicVolume.progress = curMusic
        binding.tvMusicVolPct.text = "${curMusic * 100 / maxMusic}%"

        val maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val curVoice = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        binding.seekbarVoiceVolume.max = maxVoice
        binding.seekbarVoiceVolume.progress = curVoice
        binding.tvVoiceVolPct.text = "${curVoice * 100 / maxVoice}%"
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

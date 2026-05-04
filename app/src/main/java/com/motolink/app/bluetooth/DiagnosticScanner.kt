package com.motolink.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile

enum class DiagnosticStatus { BUENO, ADVERTENCIA, ERROR }

data class DiagnosticResult(
    val status: DiagnosticStatus,
    val mensaje: String,
    val sugerencia: String
)

@SuppressLint("MissingPermission")
class DiagnosticScanner(private val headsetProxy: BluetoothHeadset?) {

    fun scanDevice(device: BluetoothDevice): DiagnosticResult {
        // 1. Bond state
        return when (device.bondState) {
            BluetoothDevice.BOND_NONE -> DiagnosticResult(
                DiagnosticStatus.ERROR,
                "Dispositivo no emparejado",
                "Ve a Ajustes → Bluetooth y empareja el intercomunicador primero"
            )
            BluetoothDevice.BOND_BONDING -> DiagnosticResult(
                DiagnosticStatus.ADVERTENCIA,
                "Emparejamiento en progreso",
                "Espera a que finalice el emparejamiento y vuelve a verificar"
            )
            else -> checkHfpState(device)
        }
    }

    private fun checkHfpState(device: BluetoothDevice): DiagnosticResult {
        // 2. HFP proxy availability
        val proxy = headsetProxy ?: return DiagnosticResult(
            DiagnosticStatus.ADVERTENCIA,
            "Perfil HFP no disponible aún",
            "El sistema tarda unos segundos en conectar el perfil HFP. Espera e intenta de nuevo."
        )

        // 3. HFP connection state
        return when (proxy.getConnectionState(device)) {
            BluetoothProfile.STATE_DISCONNECTED -> DiagnosticResult(
                DiagnosticStatus.ERROR,
                "Dispositivo desconectado del perfil HFP",
                "Acerca el intercomunicador al teléfono o presiona su botón de encendido para reconectar"
            )
            BluetoothProfile.STATE_CONNECTING -> DiagnosticResult(
                DiagnosticStatus.ADVERTENCIA,
                "Conectando al perfil HFP...",
                "Espera unos segundos mientras se establece la conexión"
            )
            else -> buildOkResult(device, proxy)
        }
    }

    private fun buildOkResult(device: BluetoothDevice, proxy: BluetoothHeadset): DiagnosticResult {
        // 4. Connected device list — determines if SCO is actually routed to this device
        val connectedDevices = proxy.connectedDevices
        val isActivelyConnected = connectedDevices.any { it.address == device.address }
        val scoDetail = if (isActivelyConnected) " · Activo en perfil HFP" else " · HFP conectado"

        // 5. Device class hint
        val classHint = device.bluetoothClass?.let {
            if (it.hasService(android.bluetooth.BluetoothClass.Service.TELEPHONY))
                " · Soporta telefonía"
            else ""
        } ?: ""

        return DiagnosticResult(
            DiagnosticStatus.BUENO,
            "Conectado via HFP$scoDetail$classHint",
            "Listo para usar el puente de audio"
        )
    }
}

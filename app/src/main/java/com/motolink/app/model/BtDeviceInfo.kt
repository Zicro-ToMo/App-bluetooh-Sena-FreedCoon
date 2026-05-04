package com.motolink.app.model

import android.bluetooth.BluetoothDevice

enum class DeviceRole { DEVICE_A, DEVICE_B }
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, SCO_ACTIVE, ERROR }

data class BtDeviceInfo(
    val device: BluetoothDevice,
    val role: DeviceRole,
    var state: ConnectionState = ConnectionState.DISCONNECTED,
    var displayName: String = device.name ?: device.address
)

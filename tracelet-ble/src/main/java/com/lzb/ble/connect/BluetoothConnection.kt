package com.lzb.ble.connect

import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.data.GattServiceInfo

interface ConnectionStateListener {
    fun onConnectionStateChange(state: BluetoothConnectionState)
}

sealed interface BluetoothConnectionState {

    data class Connected(
        val deviceInfo: BluetoothDeviceInfo,
        val services: List<GattServiceInfo>
    ) : BluetoothConnectionState

    data class Disconnected(
        val device: BluetoothDeviceInfo?,
        val status: Int?,
        val newState: Int?
    ) : BluetoothConnectionState

    data object AlreadyConnected : BluetoothConnectionState

    data object BluetoothDisabled : BluetoothConnectionState

    data object PermissionDenied : BluetoothConnectionState

    data object Timeout : BluetoothConnectionState

    object BluetoothNotSupported : BluetoothConnectionState

    data class Failed(
        val device: BluetoothDeviceInfo?,
        val status: Int?,
        val error: Throwable?
    ) : BluetoothConnectionState

}

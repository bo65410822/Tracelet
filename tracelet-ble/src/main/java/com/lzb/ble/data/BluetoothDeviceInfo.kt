package com.lzb.ble.data

import com.lzb.ble.scan.BluetoothTransport

data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val type: BluetoothTransport,
    val transports: Set<BluetoothTransport>,

    )

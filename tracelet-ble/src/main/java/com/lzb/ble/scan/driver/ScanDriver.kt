package com.lzb.ble.scan.driver

import com.lzb.ble.scan.BluetoothDeviceInfo
import com.lzb.ble.scan.BluetoothTransport

internal interface ScanDriver {
    var transport: BluetoothTransport
    fun start(listener: DriverListener): Boolean
    fun stop()
}

internal interface DriverListener {
    fun onDeviceFound(device: BluetoothDeviceInfo?)
    fun onFailed(transport: BluetoothTransport, error: Throwable?)
    fun onFinished(transport: BluetoothTransport)
}

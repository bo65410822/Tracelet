package com.lzb.ble

import android.annotation.SuppressLint
import android.content.Context
import com.lzb.ble.connect.BluetoothConnectionCoordinator
import com.lzb.ble.connect.ConnectionStateListener
import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.gatt.GattNotificationListener
import com.lzb.ble.gatt.GattNotificationResult
import com.lzb.ble.gatt.GattReadListener
import com.lzb.ble.gatt.GattReadResult
import com.lzb.ble.gatt.GattWriteListener
import com.lzb.ble.gatt.GattWriteResult
import com.lzb.ble.scan.BluetoothScanCoordinator
import com.lzb.ble.scan.ScanListener
import com.lzb.ble.scan.ScanMode

object BleManager {

    @SuppressLint("StaticFieldLeak")
    private var scanner: BluetoothScanCoordinator? = null


    @SuppressLint("StaticFieldLeak")
    private var connecter: BluetoothConnectionCoordinator? = null

    fun startScan(context: Context, mode: ScanMode = ScanMode.BLE_ONLY, listener: ScanListener) {
        if (scanner == null) {
            scanner = BluetoothScanCoordinator(context.applicationContext)
        }
        scanner?.startScan(mode, listener)
    }

    fun stopScan() {
        scanner?.stopScan()
    }

    fun connect(context: Context, deviceInfo: BluetoothDeviceInfo, listener: ConnectionStateListener) {
        if (connecter == null) {
            connecter = BluetoothConnectionCoordinator(context.applicationContext)
        }
        connecter?.connect(deviceInfo, listener)
    }

    fun disconnect() {
        connecter?.disconnect()
    }

    fun read(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattReadListener
    ) {
        val coordinator = connecter
        if (coordinator == null) {
            listener.onResult(GattReadResult.NotConnected)
            return
        }
        coordinator.read(serviceUuid, characteristicUuid, listener)
    }

    fun write(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
        listener: GattWriteListener
    ) {
        val coordinator = connecter
        if (coordinator == null) {
            listener.onResult(GattWriteResult.NotConnected)
            return
        }
        coordinator.write(serviceUuid, characteristicUuid, data, listener)
    }

    fun subscribe(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattNotificationListener
    ) {
        val coordinator = connecter
        if (coordinator == null) {
            listener.onResult(GattNotificationResult.NotConnected)
            return
        }
        coordinator.subscribe(serviceUuid, characteristicUuid, listener)
    }

}
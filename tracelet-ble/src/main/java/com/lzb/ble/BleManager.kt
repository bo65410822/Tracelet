package com.lzb.ble

import android.annotation.SuppressLint
import android.content.Context
import com.lzb.ble.scan.BluetoothScanCoordinator
import com.lzb.ble.scan.ScanListener
import com.lzb.ble.scan.ScanMode

object BleManager {

    @SuppressLint("StaticFieldLeak")
    private var scanner: BluetoothScanCoordinator? = null

    fun startScan(context: Context, mode: ScanMode = ScanMode.BLE_ONLY, listener: ScanListener) {
        if (scanner == null) {
            scanner = BluetoothScanCoordinator(context.applicationContext)
        }
        scanner?.startScan(mode, listener)
    }

    fun stopScan() {
        scanner?.stopScan()
    }
}
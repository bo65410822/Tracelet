package com.lzb.ble

import android.content.Context
import com.lzb.ble.scan.BleScannerImpl
import com.lzb.ble.scan.BluetoothScanner
import com.lzb.ble.scan.ScanListener

object BleManager {

    private var scanner: BluetoothScanner? = null

    fun startScan(context: Context, listener: ScanListener) {
        if (scanner == null) {
            scanner = BleScannerImpl(context.applicationContext)
        }
        scanner?.startScan(listener)
    }

    fun stopScan() {
        scanner?.stopScan()
    }
}
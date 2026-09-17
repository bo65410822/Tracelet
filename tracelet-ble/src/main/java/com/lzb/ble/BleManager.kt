package com.lzb.ble

import android.content.Context
import com.lzb.ble.scan.BleScannerImpl
import com.lzb.ble.scan.BluetoothScanner
import com.lzb.ble.scan.ScanListener

class BleManager {
    companion object {

        private var instance: BleManager? = null
        fun getInstance(): BleManager {
            if (instance == null) {
                instance = BleManager()
            }
            return instance!!
        }
    }

    private var scanner: BluetoothScanner? = null

    fun startScan(context: Context, listener: ScanListener) {
        if (scanner == null) {
            scanner = BleScannerImpl(context)
        }
        scanner?.startScan(listener)
    }

    fun stopScan() {
        scanner?.stopScan()
    }
}
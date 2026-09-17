package com.lzb.ble.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.lzb.ble.scan.ScanState.DeviceFound

internal class BleScannerImpl(private val context: Context) : BluetoothScanner {

    companion object {
        private const val TAG = "BleScannerImpl"
    }

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var isScanning: Boolean = false

    private lateinit var listener: ScanListener

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            listener.onStateChanged(DeviceFound(result))
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            listener.onStateChanged(ScanState.Failed(errorCode))
        }

        override fun onBatchScanResults(results: List<ScanResult?>?) {
            super.onBatchScanResults(results)
        }
    }

    override fun startScan(listener: ScanListener) {
        this.listener = listener
        // 获取蓝牙适配器
        if (bluetoothAdapter == null) {
            Log.e(TAG, "startScan: Bluetooth not supported" )
            listener.onStateChanged(ScanState.BluetoothNotSupported)
            return
        }
        // 检查是否开启蓝牙
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "startScan: Bluetooth disabled" )
            listener.onStateChanged(ScanState.BluetoothDisabled)
            return
        }

        // 检查扫描权限
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startScan: Permission denied" )
            listener.onStateChanged(ScanState.PermissionDenied)
            return
        }

        Log.i(TAG, "startScan: Starting scan" )
        // 开始扫描
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        isScanning = true
    }

    override fun stopScan() {
        if (!isScanning) return
        Log.i(TAG, "stopScan: Stopping scan" )
        isScanning = false
        val scanner = bluetoothAdapter.bluetoothLeScanner
        // 判断是否有权限
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "stopScan: Permission denied" )
            return
        }
        scanner.stopScan(scanCallback)
    }
}
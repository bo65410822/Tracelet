package com.lzb.ble.scan.driver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.scan.BluetoothTransport

/**
 * 低功耗蓝牙扫描实现类
 */
@Suppress("DEPRECATION")
internal class BleScannerImpl(override var transport: BluetoothTransport) : ScanDriver {

    companion object {
        private const val TAG = "BleScannerImpl"
    }

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    @Volatile
    private var activeScanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    override fun start(listener: DriverListener): Boolean {
        val callback = createScanCallback(listener)
        activeScanCallback = callback
        startScanInternal(callback)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal(callback: ScanCallback) {
        Log.i(TAG, "startScanInternal: Starting scan")
        try {
            val scanner = bluetoothAdapter.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        } catch (e: Exception) {
            Log.e(TAG, "startScanInternal: Exception ", e)
            clear()
            throw e
        }

    }

    private fun clear() {
        activeScanCallback = null
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        try {
            Log.i(TAG, "stopScan: Stopping scan")
            val scanner = bluetoothAdapter.bluetoothLeScanner
            val callback = activeScanCallback
            scanner.stopScan(callback)
        } finally {
            clear()
        }
    }

    private fun createScanCallback(listener: DriverListener): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                Log.i(TAG, "onScanResult: ${result?.device?.address}")
                freeFoundDev(result)
            }

            @SuppressLint("MissingPermission")
            private fun freeFoundDev(result: ScanResult?) {
                try {
                    val deviceFound = result?.let {
                        BluetoothDeviceInfo(
                            address = result.device.address,
                            name = result.device.name,
                            rssi = result.rssi,
                            type = BluetoothTransport.LE,
                            transports = setOf(BluetoothTransport.LE)
                        )
                    }
                    listener.onDeviceFound(deviceFound)
                } catch (e: Exception) {
                    Log.e(TAG, "freeFoundDev: Exception ", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "onScanFailed: $errorCode")
                try {
                    listener.onFailed(
                        BluetoothTransport.LE,
                        Exception("Scan failed with error code: $errorCode")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "onScanFailed: Exception ", e)
                }
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                super.onBatchScanResults(results)
                Log.i(TAG, "onBatchScanResults: ${results?.size}")
                results.orEmpty().forEach {
                    freeFoundDev(it)
                }
            }
        }
    }
}
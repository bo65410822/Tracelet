package com.lzb.ble.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.lzb.ble.scan.ScanState.DeviceFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
internal class BleScannerImpl(private val context: Context) : BluetoothScanner {

    companion object {
        private const val TAG = "BleScannerImpl"

        private const val SCAN_TIMEOUT = 5 * 60 * 1000L
    }

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var isScanning: Boolean = false

    private var listener: ScanListener? = null

    private val scannerJob = SupervisorJob()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scanStateScope: CoroutineScope =
        CoroutineScope(scannerJob + Dispatchers.IO.limitedParallelism(1))

    private var sessionId = 0L

    private var activeScanCallback: ScanCallback? = null

    private var scanTimeoutJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun startScan(listener: ScanListener) {
        scanStateScope.launch {
            if (isScanning) {
                Log.w(TAG, "startScan: Already scanning")
                dispatchState(listener, ScanState.AlreadyScanning)
                return@launch
            }

            if (bluetoothAdapter == null) {
                Log.e(TAG, "startScan: Bluetooth not supported")
                dispatchStateAndClear(listener, ScanState.BluetoothNotSupported)
                return@launch
            }

            if (!hasScanPermission()) {
                Log.e(TAG, "startScan: Permission denied")
                dispatchStateAndClear(listener, ScanState.PermissionDenied)
                return@launch
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "startScan: Bluetooth disabled")
                dispatchStateAndClear(listener, ScanState.BluetoothDisabled)
                return@launch
            }

            var currSessionId = ++sessionId
            this@BleScannerImpl.listener = listener
            val callback = createScanCallback(currSessionId)
            activeScanCallback = callback
            startScanInternal(listener, callback, currSessionId)
        }
    }

    private fun startScanInternal(listener: ScanListener, callback: ScanCallback, sessionId: Long) {
        Log.i(TAG, "startScanInternal: Starting scan")
        try {
            val scanner = bluetoothAdapter.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(emptyList<ScanFilter>(), settings, callback)
            isScanning = true
            scheduleScanTimeout(sessionId)
            dispatchState(listener, ScanState.Scanning)
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException ${e.message}")
            dispatchStateAndClear(listener, ScanState.PermissionDenied)
        } catch (e: Exception) {
            Log.e(TAG, "startScan: Exception ${e.message}")
            dispatchStateAndClear(listener, ScanState.Unknown(e.message))
        }
    }

    private fun dispatchStateAndClear(target: ScanListener?, state: ScanState) {
        clear()
        dispatchState(target, state)
    }

    private fun scheduleScanTimeout(sessionId: Long) {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scanStateScope.launch {
            delay(SCAN_TIMEOUT)
            if (checkSessionId(sessionId)) {
                return@launch
            }
            Log.w(TAG, "Scan timeout")
            stopScanInternal(ScanState.Timeout)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        scanStateScope.launch {
            if (!isScanning) return@launch
            Log.i(TAG, "stopScan: Stopping scan")
            stopScanInternal(ScanState.Stopped)
        }
    }

    private fun stopScanInternal(state: ScanState) {
        try {
            if (!hasScanPermission()) {
                Log.e(TAG, "stopScanInternal: Permission denied")
                return
            }
            val scanner = bluetoothAdapter.bluetoothLeScanner
            val callback = activeScanCallback
            scanner.stopScan(callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "stopScanInternal: SecurityException ${e.message}")
        } finally {
            dispatchStateAndClear(listener, state)
        }
    }

    private fun dispatchState(target: ScanListener?, state: ScanState) {
        if (target == null) {
            return
        }
        try {
            target.onStateChanged(state)
        } catch (e: Exception) {
            Log.e(TAG, "dispatchState: Exception ", e)
        }
    }

    private fun clear() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        isScanning = false
        activeScanCallback = null
        listener = null
    }

    private fun hasScanPermission(): Boolean {
        // 是否有蓝牙权限
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun createScanCallback(sessionId: Long): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                Log.i(TAG, "onScanResult: ${result?.device?.address}")
                scanStateScope.launch {
                    freeFoundDev(result)
                }
            }

            private fun freeFoundDev(result: ScanResult?) {
                if (checkSessionId(sessionId)) {
                    return
                }
                dispatchState(listener, DeviceFound(result))
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "onScanFailed: $errorCode")
                scanStateScope.launch {
                    if (checkSessionId(sessionId)) {
                        return@launch
                    }
                    dispatchStateAndClear(listener, ScanState.Failed(errorCode))
                }
            }

            override fun onBatchScanResults(results: List<ScanResult?>?) {
                super.onBatchScanResults(results)
                Log.i(TAG, "onBatchScanResults: ${results?.size}")
                scanStateScope.launch {
                    results.orEmpty().forEach {
                        freeFoundDev(it)
                    }
                }
            }
        }
    }

    private fun checkSessionId(sessionId: Long): Boolean {
        Log.w(
            TAG, "checkSessionId: sessionId  ${this@BleScannerImpl.sessionId} : $sessionId"
        )
        return (this.sessionId != sessionId || !isScanning)
    }
}
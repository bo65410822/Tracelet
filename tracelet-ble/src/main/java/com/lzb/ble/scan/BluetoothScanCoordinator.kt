package com.lzb.ble.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.lzb.ble.scan.driver.BleScannerImpl
import com.lzb.ble.scan.driver.ClassicScannerImpl
import com.lzb.ble.scan.driver.DriverListener
import com.lzb.ble.scan.driver.ScanDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
internal class BluetoothScanCoordinator(private val context: Context) {

    companion object {
        const val TAG = "BluetoothScanCoordinator"
        private const val SCAN_TIMEOUT = 5 * 60 * 1000L
    }

    private var isScanning: Boolean = false

    private var currentSessionId: Long = 0L

    private var listener: ScanListener? = null

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var selectedDrivers: List<ScanDriver> = emptyList()

    private val activeTransports: MutableSet<BluetoothTransport> = mutableSetOf()

    private var scanTimeoutJob: Job? = null

    private val scannerJob = SupervisorJob()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scanStateScope: CoroutineScope =
        CoroutineScope(scannerJob + Dispatchers.IO.limitedParallelism(1))

    fun startScan(mode: ScanMode = ScanMode.BLE_ONLY, listener: ScanListener) {
        scanStateScope.launch {
            if (isScanning) {
                Log.w(TAG, "startScan: Already scanning")
                safeDispatch(listener, ScanState.AlreadyScanning)
                return@launch
            }
            if (bluetoothAdapter == null) {
                Log.e(TAG, "startScan: Bluetooth not supported")
                finishScan(listener, ScanState.BluetoothNotSupported)
                return@launch
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.e(TAG, "startScan: Bluetooth disabled")
                finishScan(listener, ScanState.BluetoothDisabled)
                return@launch
            }

            if (!hasScanPermission()) {
                Log.e(TAG, "startScan: Permission denied")
                finishScan(listener, ScanState.PermissionDenied)
                return@launch
            }
            this@BluetoothScanCoordinator.listener = listener
            startScanInner(mode, listener)
        }
    }

    private fun startScanInner(mode: ScanMode, listener: ScanListener) {
        try {
            selectDrivers(mode)
            var currSessionId = ++currentSessionId
            val start = startSelectedDrivers(currSessionId, listener)
            if (start) {
                isScanning = true
                safeDispatch(listener, ScanState.Scanning)
                scheduleScanTimeout(listener, currSessionId)
            } else {
                safeDispatch(
                    listener,
                    ScanState.Failed(BluetoothTransport.ALL, Exception("Failed to start scan"))
                )
                clear()
                Log.e(TAG, "startScan: Failed to start scan")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: SecurityException ${e.message}")
            finishScan(listener, ScanState.PermissionDenied)
        } catch (e: Exception) {
            Log.e(TAG, "startScan: Exception ${e.message}")
            finishScan(listener, ScanState.Unknown(e.message))
        }
    }

    /**
     * 开启扫描
     * BLE 和经典蓝牙 同时扫描，只要有一个成功即可
     */
    private fun startSelectedDrivers(currSessionId: Long, listener: ScanListener): Boolean {
        val tmpListener = createDriverListener(currSessionId, listener)
        val activeDrivers = selectedDrivers
        var started = false
        activeTransports.clear()
        activeDrivers.forEach {
            try {
                val start = it.start(tmpListener)
                if (start) {
                    activeTransports += it.transport
                    Log.i(
                        TAG,
                        "startSelectedDrivers: started ${it.javaClass.simpleName} ${it.transport}"
                    )
                    started = true
                } else {
                    Log.e(TAG, "startScan: Failed to start scan ${it.javaClass.simpleName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startScan: e:", e)
            }
        }
        return started
    }

    private fun selectDrivers(mode: ScanMode) {
        selectedDrivers = when (mode) {
            ScanMode.BLE_ONLY -> {
                listOf(BleScannerImpl(BluetoothTransport.LE))
            }

            ScanMode.CLASSIC_ONLY -> {
                listOf(ClassicScannerImpl(context, BluetoothTransport.CLASSIC))
            }

            ScanMode.BOTH -> {
                listOf(
                    BleScannerImpl(BluetoothTransport.LE),
                    ClassicScannerImpl(context, BluetoothTransport.CLASSIC)
                )
            }
        }
    }

    private fun createDriverListener(currSessionId: Long, listener: ScanListener): DriverListener {
        val listener = object : DriverListener {
            override fun onDeviceFound(device: BluetoothDeviceInfo?) {
                scanStateScope.launch {
                    if (checkSessionId(currSessionId)) {
                        return@launch
                    }
                    safeDispatch(listener, ScanState.DeviceFound(device))
                }
            }

            override fun onFailed(transport: BluetoothTransport, error: Throwable?) {
                scanStateScope.launch {
                    disExceptionState(transport, ScanState.DriverFailed(transport))
                }
            }

            override fun onFinished(transport: BluetoothTransport) {
                scanStateScope.launch {
                    disExceptionState(transport, ScanState.DriverFailed(transport))
                }
            }

            private fun disExceptionState(transport: BluetoothTransport, state: ScanState) {
                Log.i(TAG, "disExceptionState: $transport ${state.javaClass.simpleName}")
                if (checkSessionId(currSessionId)) {
                    return
                }
                if (!activeTransports.remove(transport)) {
                    return
                }
                if (activeTransports.isEmpty()) {
                    stopScanInternal(listener, state)
                } else {
                    safeDispatch(listener, state)
                }
            }
        }
        return listener
    }

    fun stopScan() {
        scanStateScope.launch {
            if (!isScanning) {
                Log.w(TAG, "stopScanInternal: Not scanning")
                return@launch
            }
            stopScanInternal(listener, ScanState.Stopped)
        }
    }

    private fun stopScanInternal(listener: ScanListener?, state: ScanState) {
        selectedDrivers.forEach {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.e(TAG, "stopScanInternal: e:", e)
            }
        }
        finishScan(listener, state)
    }

    private fun finishScan(listener: ScanListener?, state: ScanState) {
        clear()
        safeDispatch(listener, state)
    }

    private fun clear() {
        isScanning = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        listener = null
        activeTransports.clear()
    }

    private fun scheduleScanTimeout(listener: ScanListener, sessionId: Long) {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scanStateScope.launch {
            if (checkSessionId(sessionId)) {
                return@launch
            }
            delay(SCAN_TIMEOUT)
            Log.w(TAG, "Scan timeout")
            stopScanInternal(listener, ScanState.Timeout)
        }
    }

    private fun safeDispatch(listener: ScanListener?, state: ScanState) {
        try {
            listener?.onStateChanged(state)
        } catch (e: Exception) {
            Log.e(TAG, "safeDispatch: ${e.message}")
        }
    }

    private fun checkSessionId(sessionId: Long): Boolean {
        Log.w(
            TAG,
            "checkSessionId: sessionId  ${this@BluetoothScanCoordinator.currentSessionId} : $sessionId"
        )
        return (this.currentSessionId != sessionId || !isScanning)
    }


    private fun hasScanPermission(): Boolean {
        // 是否有蓝牙权限
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
                    &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
}
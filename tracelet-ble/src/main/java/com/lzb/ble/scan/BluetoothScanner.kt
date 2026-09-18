package com.lzb.ble.scan

import android.bluetooth.le.ScanResult


internal interface BluetoothScanner {

    fun startScan(listener: ScanListener)

    fun stopScan()

}

interface ScanListener {
    fun onStateChanged(state: ScanState)
}

sealed interface ScanState {

    /**
     * 扫描中
     */
    data object Scanning : ScanState

    /**
     * 已经开始扫描了
     */
    data object AlreadyScanning : ScanState

    /**
     * 扫描到设备
     */
    data class DeviceFound(val result: ScanResult?) : ScanState

    /**
     * 蓝牙未启用
     */
    data object BluetoothDisabled : ScanState

    /**
     * 蓝牙不支持
     */
    data object BluetoothNotSupported : ScanState

    /**
     * 权限被拒绝
     */
    data object PermissionDenied : ScanState

    /**
     * 扫描超时
     */
    data object Timeout : ScanState

    /**
     * 扫描停止
     */
    data object Stopped : ScanState

    /**
     * 扫描失败
     */
    data class Failed(val code: Int?) : ScanState

    data class Unknown(val message: String?) : ScanState

}
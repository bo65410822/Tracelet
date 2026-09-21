package com.lzb.ble.scan.driver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.scan.BluetoothTransport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 经典蓝牙扫描器实现
 */
@Suppress("DEPRECATION")
internal class ClassicScannerImpl(private val context: Context,
                                  override var transport: BluetoothTransport
) : ScanDriver {

    companion object {
        const val TAG = "ClassicScannerImpl"

        private const val ACTION_DISCOVERY_FINISHED = BluetoothAdapter.ACTION_DISCOVERY_FINISHED
        private const val ACTION_FOUND = BluetoothDevice.ACTION_FOUND
    }

    @Volatile
    private var listener: DriverListener? = null

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private val registered = AtomicBoolean(false)

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "onReceive: Discovery finished")
                    listener?.onFinished(BluetoothTransport.CLASSIC)
                    clear()
                }

                ACTION_FOUND -> foundDevice(intent)
            }
        }
    }

    private fun foundDevice(intent: Intent?) {
        try {
            val device =
                intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val rssi =
                intent?.getShortExtra(BluetoothDevice.EXTRA_RSSI, -1) ?: -1
            device?.let {
                Log.d(TAG, "onReceive: Found device ${it.name}, ${it.address}")
                val bluetoothDeviceInfo = BluetoothDeviceInfo(
                    address = it.address,
                    name = it.name,
                    type = BluetoothTransport.CLASSIC,
                    transports = setOf(BluetoothTransport.CLASSIC),
                    rssi = rssi.toInt()
                )
                listener?.onDeviceFound(bluetoothDeviceInfo)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "foundDevice: ${e.message}")
            listener?.onFailed(BluetoothTransport.CLASSIC, e)
            clear()
        }
    }


    @SuppressLint("MissingPermission")
    override fun start(listener: DriverListener): Boolean {
        try {
            this.listener = listener
            Log.d(TAG, "startScan: Starting scan")
            val filter = IntentFilter(ACTION_FOUND)
            filter.addAction(ACTION_DISCOVERY_FINISHED)
            context.registerReceiver(bluetoothReceiver, filter)
            registered.set(true)
            val startDiscovery = bluetoothAdapter.startDiscovery()
            if (startDiscovery) {
                Log.d(TAG, "startScan: Discovery started")
            } else {
                clear()
            }
            return startDiscovery
        } catch (e: Exception) {
            clear()
            Log.e(TAG, "startScan: ${e.message}")
            throw e
        }
    }


    override fun stop() {
        try {
            clear()
        } catch (e: Exception) {
            Log.e(TAG, "stopScan: ${e.message}")
            throw e
        }
        Log.i(TAG, "stopScan: Discovery stopped")
    }

    @SuppressLint("MissingPermission")
    private fun clear() {
        listener = null
        unregisterReceiverSafely()
        try {
            val cancelDiscovery = bluetoothAdapter.cancelDiscovery()
            if (cancelDiscovery) {
                Log.i(TAG, "stopScan: Discovery stop succeeded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "clear: ${e.message}")
        }
    }

    private fun unregisterReceiverSafely() {
        if (registered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "unregisterReceiverSafely: e", e)
            }
        } else {
            Log.w(TAG, "unregisterReceiverSafely: Receiver was not registered")
        }
    }
}
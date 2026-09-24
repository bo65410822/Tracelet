package com.lzb.ble.connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.lzb.ble.data.BluetoothDeviceInfo
import com.lzb.ble.data.GattServiceInfo
import com.lzb.ble.gatt.GattCharacteristic
import com.lzb.ble.gatt.GattNotificationListener
import com.lzb.ble.gatt.GattOperationController
import com.lzb.ble.gatt.GattReadListener
import com.lzb.ble.gatt.GattWriteListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class BluetoothConnectionCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothConnectionCoordinator"
        const val CONNECT_TIMEOUT = 10_000L
    }

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var activeSessionId: Long? = null
    private var sessionCounter = 0L
    private var isConnected = false
    private var deviceAddress: String? = null
    private var currGattCallback: BluetoothGattCallback? = null
    private var currConnectGatt: BluetoothGatt? = null
    private var connectTimeoutJob: Job? = null
    private val connectJob = SupervisorJob()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectStateScope: CoroutineScope =
        CoroutineScope(connectJob + Dispatchers.IO.limitedParallelism(1))

    private val gattOperationController = GattOperationController(
        scope = connectStateScope,
        currentGatt = { currConnectGatt },
        currentSessionId = { activeSessionId },
        isReady = { isConnected },
        isCurrentSession = ::checkSessionId
    )

    fun connect(deviceInfo: BluetoothDeviceInfo, listener: ConnectionStateListener) {
        connectStateScope.launch {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                Log.e(TAG, "Bluetooth not supported on this device")
                dispatchStateNoSession(listener, BluetoothConnectionState.BluetoothNotSupported)
                return@launch
            }
            if (isConnected && deviceAddress == deviceInfo.address) {
                Log.w(TAG, "Already connected")
                dispatchStateNoSession(listener, BluetoothConnectionState.AlreadyConnected)
                return@launch
            }
            if (!adapter.isEnabled) {
                Log.w(TAG, "Bluetooth is disabled")
                dispatchStateNoSession(listener, BluetoothConnectionState.BluetoothDisabled)
                return@launch
            }
            if (!hasConnectPermission()) {
                Log.w(TAG, "Permission denied")
                dispatchStateNoSession(listener, BluetoothConnectionState.PermissionDenied)
                return@launch
            }
            if (currConnectGatt != null || activeSessionId != null) {
                Log.i(TAG, "Replacing active connection")
                disconnectInternal()
            }
            connectInternal(deviceInfo, listener, adapter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(
        deviceInfo: BluetoothDeviceInfo,
        listener: ConnectionStateListener,
        adapter: BluetoothAdapter
    ) {
        val address = deviceInfo.address
        deviceAddress = address
        val sessionId = ++sessionCounter
        activeSessionId = sessionId
        Log.i(TAG, "Connecting to $address")
        val callback = createGattCallback(deviceInfo, listener, sessionId)
        val gatt = try {
            adapter.getRemoteDevice(address).connectGatt(context, false, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to $address", e)
            finishConnection(
                sessionId,
                listener,
                BluetoothConnectionState.Failed(deviceInfo, null, e)
            )
            return
        }
        if (gatt == null) {
            finishConnection(
                sessionId,
                listener,
                BluetoothConnectionState.Failed(
                    deviceInfo,
                    null,
                    IllegalStateException("connectGatt returned null")
                )
            )
            return
        }
        currGattCallback = callback
        currConnectGatt = gatt
        connectTimeoutJob = connectStateScope.launch {
            delay(CONNECT_TIMEOUT)
            finishConnection(sessionId, listener, BluetoothConnectionState.Timeout)
        }
    }

    private fun createGattCallback(
        deviceInfo: BluetoothDeviceInfo,
        listener: ConnectionStateListener,
        sessionId: Long
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                connectStateScope.launch {
                    if (!checkSessionId(sessionId)) return@launch
                    if (finishIfGattFailed(sessionId, status, listener, deviceInfo)) return@launch
                    when (newState) {
                        BluetoothGatt.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected to ${deviceInfo.address}")
                            if (!gatt.discoverServices()) {
                                finishConnection(
                                    sessionId,
                                    listener,
                                    BluetoothConnectionState.Failed(
                                        deviceInfo,
                                        status,
                                        IllegalStateException("discoverServices returned false")
                                    )
                                )
                            }
                        }

                        BluetoothGatt.STATE_DISCONNECTED -> finishConnection(
                            sessionId,
                            listener,
                            BluetoothConnectionState.Disconnected(deviceInfo, status, newState)
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                connectStateScope.launch {
                    if (!checkSessionId(sessionId)) return@launch
                    if (finishIfGattFailed(sessionId, status, listener, deviceInfo)) return@launch
                    val services = gatt.services.map { service ->
                        GattServiceInfo(
                            uuid = service.uuid.toString(),
                            isPrimary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
                            characteristics = service.characteristics.map { characteristic ->
                                GattCharacteristic(
                                    serviceUuid = service.uuid.toString(),
                                    uuid = characteristic.uuid.toString(),
                                    properties = characteristic.properties
                                )
                            }
                        )
                    }
                    isConnected = true
                    connectTimeoutJob?.cancel()
                    dispatchState(
                        sessionId,
                        listener,
                        BluetoothConnectionState.Connected(deviceInfo, services)
                    )
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                gattOperationController.onCharacteristicRead(
                    sessionId,
                    characteristic,
                    value,
                    status
                )
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                gattOperationController.onCharacteristicRead(
                    sessionId,
                    characteristic,
                    value,
                    status
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                gattOperationController.onCharacteristicWrite(sessionId, characteristic, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                gattOperationController.onDescriptorWrite(sessionId, descriptor, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                gattOperationController.onCharacteristicChanged(sessionId, characteristic, value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                gattOperationController.onCharacteristicChanged(sessionId, characteristic, value)
            }
        }
    }

    private fun finishIfGattFailed(
        sessionId: Long,
        status: Int,
        listener: ConnectionStateListener,
        deviceInfo: BluetoothDeviceInfo
    ): Boolean {
        if (status == BluetoothGatt.GATT_SUCCESS) return false
        Log.w(TAG, "GATT operation failed with status: $status")
        finishConnection(
            sessionId,
            listener,
            BluetoothConnectionState.Failed(deviceInfo, status, null)
        )
        return true
    }

    fun read(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattReadListener
    ) = gattOperationController.read(serviceUuid, characteristicUuid, listener)

    fun write(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
        listener: GattWriteListener
    ) = gattOperationController.write(serviceUuid, characteristicUuid, data, listener)

    fun subscribe(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattNotificationListener
    ) = gattOperationController.subscribe(serviceUuid, characteristicUuid, listener)

    fun disconnect() {
        connectStateScope.launch { disconnectInternal() }
    }

    private fun dispatchState(
        sessionId: Long,
        listener: ConnectionStateListener,
        state: BluetoothConnectionState
    ) {
        if (!checkSessionId(sessionId)) return
        dispatchStateNoSession(listener, state)
    }

    private fun dispatchStateNoSession(
        listener: ConnectionStateListener,
        state: BluetoothConnectionState
    ) {
        try {
            listener.onConnectionStateChange(state)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching state", e)
        }
    }

    private fun finishConnection(
        sessionId: Long,
        listener: ConnectionStateListener,
        state: BluetoothConnectionState
    ) {
        dispatchState(sessionId, listener, state)
        disconnectInternal()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal() {
        connectTimeoutJob?.cancel()
        gattOperationController.onDisconnected()
        try {
            currConnectGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT connection", e)
        } finally {
            activeSessionId = null
            currGattCallback = null
            currConnectGatt = null
            isConnected = false
            deviceAddress = null
        }
    }

    private fun checkSessionId(sessionId: Long): Boolean {
        return sessionId == activeSessionId
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

package com.lzb.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal class GattOperationController(
    private val scope: CoroutineScope,
    private val currentGatt: () -> BluetoothGatt?,
    private val currentSessionId: () -> Long?,
    private val isReady: () -> Boolean,
    private val isCurrentSession: (Long) -> Boolean
) {
    companion object {
        private const val TAG = "GattOperationController"
        private const val OPERATION_TIMEOUT = 10_000L
        private const val DEFAULT_MAX_WRITE_SIZE = 20
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private sealed interface PendingOperation {
        val sessionId: Long

        data class Read(
            override val sessionId: Long,
            val serviceUuid: String,
            val characteristicUuid: String,
            val listener: GattReadListener
        ) : PendingOperation

        data class Write(
            override val sessionId: Long,
            val serviceUuid: String,
            val characteristicUuid: String,
            val listener: GattWriteListener
        ) : PendingOperation

        data class Subscribe(
            override val sessionId: Long,
            val serviceUuid: String,
            val characteristicUuid: String,
            val listener: GattNotificationListener
        ) : PendingOperation
    }

    private var pendingOperation: PendingOperation? = null
    private var operationTimeoutJob: Job? = null
    private val notificationListeners =
        mutableMapOf<Pair<String, String>, GattNotificationListener>()

    @SuppressLint("MissingPermission")
    fun read(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattReadListener
    ) {
        scope.launch {
            val sessionId = currentSessionId()
            val gatt = currentGatt()
            if (!isReady() || sessionId == null || gatt == null) {
                dispatchReadResult(listener, GattReadResult.NotConnected)
                return@launch
            }
            if (pendingOperation != null) {
                dispatchReadResult(listener, GattReadResult.Busy)
                return@launch
            }
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            if (characteristic == null) {
                dispatchReadResult(listener, GattReadResult.CharacteristicNotFound)
                return@launch
            }
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                dispatchReadResult(listener, GattReadResult.ReadNotSupported)
                return@launch
            }

            pendingOperation = PendingOperation.Read(
                sessionId,
                serviceUuid,
                characteristicUuid,
                listener
            )
            val started = try {
                gatt.readCharacteristic(characteristic)
            } catch (e: Exception) {
                finishRead(listener, GattReadResult.Failed(null, e))
                return@launch
            }
            if (!started) {
                finishRead(
                    listener,
                    GattReadResult.Failed(
                        null,
                        IllegalStateException("readCharacteristic returned false")
                    )
                )
                return@launch
            }
            scheduleTimeout(sessionId)
        }
    }

    @SuppressLint("MissingPermission")
    fun write(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
        listener: GattWriteListener
    ) {
        val value = data.copyOf()
        scope.launch {
            val sessionId = currentSessionId()
            val gatt = currentGatt()
            if (!isReady() || sessionId == null || gatt == null) {
                dispatchWriteResult(listener, GattWriteResult.NotConnected)
                return@launch
            }
            if (pendingOperation != null) {
                dispatchWriteResult(listener, GattWriteResult.Busy)
                return@launch
            }
            if (value.size > DEFAULT_MAX_WRITE_SIZE) {
                dispatchWriteResult(listener, GattWriteResult.DataTooLarge)
                return@launch
            }
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            if (characteristic == null) {
                dispatchWriteResult(listener, GattWriteResult.CharacteristicNotFound)
                return@launch
            }
            val supportsWrite =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val supportsWriteWithoutResponse =
                characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
            if (!supportsWrite && !supportsWriteWithoutResponse) {
                dispatchWriteResult(listener, GattWriteResult.WriteNotSupported)
                return@launch
            }
            val writeType = if (supportsWrite) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            pendingOperation = PendingOperation.Write(
                sessionId,
                serviceUuid,
                characteristicUuid,
                listener
            )
            val started = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, value, writeType) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = writeType
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            } catch (e: Exception) {
                finishWrite(listener, GattWriteResult.Failed(null, e))
                return@launch
            }
            if (!started) {
                finishWrite(
                    listener,
                    GattWriteResult.Failed(
                        null,
                        IllegalStateException("writeCharacteristic was not started")
                    )
                )
                return@launch
            }
            scheduleTimeout(sessionId)
        }
    }

    @SuppressLint("MissingPermission")
    fun subscribe(
        serviceUuid: String,
        characteristicUuid: String,
        listener: GattNotificationListener
    ) {
        scope.launch {
            val sessionId = currentSessionId()
            val gatt = currentGatt()
            if (!isReady() || sessionId == null || gatt == null) {
                dispatchNotificationResult(listener, GattNotificationResult.NotConnected)
                return@launch
            }
            if (pendingOperation != null) {
                dispatchNotificationResult(listener, GattNotificationResult.Busy)
                return@launch
            }
            val characteristic = findCharacteristic(gatt, serviceUuid, characteristicUuid)
            if (characteristic == null) {
                dispatchNotificationResult(
                    listener,
                    GattNotificationResult.CharacteristicNotFound
                )
                return@launch
            }
            val supportsIndicate =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val supportsNotify =
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            if (!supportsNotify && !supportsIndicate) {
                dispatchNotificationResult(
                    listener,
                    GattNotificationResult.NotificationNotSupported
                )
                return@launch
            }
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                dispatchNotificationResult(
                    listener,
                    GattNotificationResult.CharacteristicNotFound
                )
                return@launch
            }

            val notificationEnabled = try {
                gatt.setCharacteristicNotification(characteristic, true)
            } catch (e: Exception) {
                dispatchNotificationResult(listener, GattNotificationResult.Failed(null, e))
                return@launch
            }
            if (!notificationEnabled) {
                dispatchNotificationResult(
                    listener,
                    GattNotificationResult.Failed(
                        null,
                        IllegalStateException("setCharacteristicNotification returned false")
                    )
                )
                return@launch
            }

            pendingOperation = PendingOperation.Subscribe(
                sessionId,
                serviceUuid,
                characteristicUuid,
                listener
            )
            val descriptorValue = if (supportsIndicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = descriptorValue
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            } catch (e: Exception) {
                finishSubscribe(listener, GattNotificationResult.Failed(null, e))
                return@launch
            }
            if (!started) {
                finishSubscribe(
                    listener,
                    GattNotificationResult.Failed(
                        null,
                        IllegalStateException("writeDescriptor was not started")
                    )
                )
                return@launch
            }
            scheduleTimeout(sessionId)
        }
    }

    fun onCharacteristicRead(
        sessionId: Long,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        scope.launch {
            if (!isCurrentSession(sessionId)) return@launch
            val pending = pendingOperation as? PendingOperation.Read ?: return@launch
            if (!matches(pending.serviceUuid, pending.characteristicUuid, characteristic)) {
                return@launch
            }
            val result = if (status == BluetoothGatt.GATT_SUCCESS) {
                GattReadResult.Success(value.copyOf())
            } else {
                GattReadResult.Failed(status, null)
            }
            finishRead(pending.listener, result)
        }
    }

    fun onCharacteristicWrite(
        sessionId: Long,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        scope.launch {
            if (!isCurrentSession(sessionId)) return@launch
            val pending = pendingOperation as? PendingOperation.Write ?: return@launch
            if (!matches(pending.serviceUuid, pending.characteristicUuid, characteristic)) {
                return@launch
            }
            val result = if (status == BluetoothGatt.GATT_SUCCESS) {
                GattWriteResult.Success
            } else {
                GattWriteResult.Failed(status, null)
            }
            finishWrite(pending.listener, result)
        }
    }

    fun onDescriptorWrite(
        sessionId: Long,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        scope.launch {
            if (!isCurrentSession(sessionId)) return@launch
            val pending = pendingOperation as? PendingOperation.Subscribe ?: return@launch
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                !matches(pending.serviceUuid, pending.characteristicUuid, descriptor.characteristic)
            ) {
                return@launch
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                notificationListeners[pending.serviceUuid to pending.characteristicUuid] =
                    pending.listener
                finishSubscribe(pending.listener, GattNotificationResult.Subscribed)
            } else {
                finishSubscribe(pending.listener, GattNotificationResult.Failed(status, null))
            }
        }
    }

    fun onCharacteristicChanged(
        sessionId: Long,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        scope.launch {
            if (!isCurrentSession(sessionId)) return@launch
            val serviceUuid = characteristic.service.uuid.toString()
            val characteristicUuid = characteristic.uuid.toString()
            val listener = notificationListeners[serviceUuid to characteristicUuid] ?: return@launch
            try {
                listener.onData(serviceUuid, characteristicUuid, value.copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "Error dispatching notification data", e)
            }
        }
    }

    fun onDisconnected() {
        when (val pending = pendingOperation) {
            is PendingOperation.Read -> finishRead(pending.listener, GattReadResult.NotConnected)
            is PendingOperation.Write -> finishWrite(pending.listener, GattWriteResult.NotConnected)
            is PendingOperation.Subscribe ->
                finishSubscribe(pending.listener, GattNotificationResult.NotConnected)
            null -> Unit
        }
        notificationListeners.clear()
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: String,
        characteristicUuid: String
    ): BluetoothGattCharacteristic? {
        val serviceId = runCatching { UUID.fromString(serviceUuid) }.getOrNull() ?: return null
        val characteristicId =
            runCatching { UUID.fromString(characteristicUuid) }.getOrNull() ?: return null
        return gatt.getService(serviceId)?.getCharacteristic(characteristicId)
    }

    private fun matches(
        serviceUuid: String,
        characteristicUuid: String,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return characteristic.service.uuid.toString().equals(serviceUuid, ignoreCase = true) &&
            characteristic.uuid.toString().equals(characteristicUuid, ignoreCase = true)
    }

    private fun scheduleTimeout(sessionId: Long) {
        operationTimeoutJob?.cancel()
        operationTimeoutJob = scope.launch {
            delay(OPERATION_TIMEOUT)
            if (!isCurrentSession(sessionId)) return@launch
            when (val pending = pendingOperation) {
                is PendingOperation.Read -> finishRead(pending.listener, GattReadResult.Timeout)
                is PendingOperation.Write -> finishWrite(pending.listener, GattWriteResult.Timeout)
                is PendingOperation.Subscribe ->
                    finishSubscribe(pending.listener, GattNotificationResult.Timeout)
                null -> Unit
            }
        }
    }

    private fun finishRead(listener: GattReadListener, result: GattReadResult) {
        clearPendingOperation()
        dispatchReadResult(listener, result)
    }

    private fun finishWrite(listener: GattWriteListener, result: GattWriteResult) {
        clearPendingOperation()
        dispatchWriteResult(listener, result)
    }

    private fun finishSubscribe(
        listener: GattNotificationListener,
        result: GattNotificationResult
    ) {
        clearPendingOperation()
        dispatchNotificationResult(listener, result)
    }

    private fun clearPendingOperation() {
        operationTimeoutJob?.cancel()
        operationTimeoutJob = null
        pendingOperation = null
    }

    private fun dispatchReadResult(listener: GattReadListener, result: GattReadResult) {
        try {
            listener.onResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching read result", e)
        }
    }

    private fun dispatchWriteResult(listener: GattWriteListener, result: GattWriteResult) {
        try {
            listener.onResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching write result", e)
        }
    }

    private fun dispatchNotificationResult(
        listener: GattNotificationListener,
        result: GattNotificationResult
    ) {
        try {
            listener.onResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching notification result", e)
        }
    }
}

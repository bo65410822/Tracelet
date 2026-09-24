package com.lzb.ble.gatt

interface GattReadListener {
    fun onResult(result: GattReadResult)
}

sealed interface GattReadResult {
    data class Success(val data: ByteArray) : GattReadResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    data object NotConnected : GattReadResult
    data object CharacteristicNotFound : GattReadResult
    data object ReadNotSupported : GattReadResult
    data object Busy : GattReadResult
    data object Timeout : GattReadResult
    data class Failed(val status: Int?, val error: Throwable?) : GattReadResult
}

interface GattNotificationListener {
    fun onResult(result: GattNotificationResult)

    fun onData(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray
    )
}

sealed interface GattNotificationResult {
    data object Subscribed : GattNotificationResult
    data object NotConnected : GattNotificationResult
    data object CharacteristicNotFound : GattNotificationResult
    data object NotificationNotSupported : GattNotificationResult
    data object Busy : GattNotificationResult
    data object Timeout : GattNotificationResult
    data class Failed(val status: Int?, val error: Throwable?) : GattNotificationResult
}

interface GattWriteListener {
    fun onResult(result: GattWriteResult)
}


sealed interface GattWriteResult {
    data object Success : GattWriteResult
    data object NotConnected : GattWriteResult
    data object CharacteristicNotFound : GattWriteResult
    data object WriteNotSupported : GattWriteResult
    data object Busy : GattWriteResult
    data object DataTooLarge : GattWriteResult
    data object Timeout : GattWriteResult
    data class Failed(val status: Int?, val error: Throwable?) : GattWriteResult
}
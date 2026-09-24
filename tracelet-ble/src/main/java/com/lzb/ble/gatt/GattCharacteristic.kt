package com.lzb.ble.gatt

data class GattCharacteristic(
    val serviceUuid: String,
    val uuid: String,
    val properties: Int
)
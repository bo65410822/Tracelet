package com.lzb.ble.data

import com.lzb.ble.gatt.GattCharacteristic

data class GattServiceInfo (
    val uuid: String,
    val isPrimary: Boolean,
    val characteristics: List<GattCharacteristic>
)

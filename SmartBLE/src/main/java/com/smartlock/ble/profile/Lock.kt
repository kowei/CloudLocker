package com.smartlock.ble.profile

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.smartlock.ble.service.GATTDefinitions
import java.util.*

open class Lock(context: Context) {
    private var lockerService: BluetoothGattService
    private var notifyChar: BluetoothGattCharacteristic
    private var secretChar: BluetoothGattCharacteristic
    private var caliChar: BluetoothGattCharacteristic

    companion object SMART_LOCK_UUID {
        val SMART_LOCK_LOCKER_SERVICE_UUID = UUID
                .fromString("0000000a-0000-8888-6666-99999999ffff")
        val SMART_LOCK_NOTIFY_UUID = UUID
                .fromString("00000001-0000-8888-6666-99999999ffff")
        val SMART_LOCK_SECRET_UUID = UUID
                .fromString("00000002-0000-8888-6666-99999999ffff")
        val SMART_LOCK_CALI_UUID = UUID
                .fromString("00000003-0000-8888-6666-99999999ffff")
    }

    private val SMART_LOCK_DESCRIPTION = "Smart IoT Locker"


    init {
        lockerService = BluetoothGattService(
                SMART_LOCK_LOCKER_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        notifyChar = BluetoothGattCharacteristic(
                SMART_LOCK_NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                /* No permissions */ 0
        )
        caliChar = BluetoothGattCharacteristic(
                SMART_LOCK_CALI_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        secretChar = BluetoothGattCharacteristic(
                SMART_LOCK_SECRET_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
        )
        notifyChar.addDescriptor(getSigClientDescriptor())
        notifyChar.addDescriptor(getSigUserDescriptor(SMART_LOCK_DESCRIPTION))
        secretChar.value = "this is a test".toByteArray(charset("UTF-8"))

        lockerService.addCharacteristic(notifyChar)
        lockerService.addCharacteristic(secretChar)
        lockerService.addCharacteristic(caliChar)
    }

    private fun getSigClientDescriptor(): BluetoothGattDescriptor {
        val descriptor = BluetoothGattDescriptor(
                GATTDefinitions.SIG_CLIENT_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        descriptor.value = byteArrayOf(0, 0)
        return descriptor
    }

    private fun getSigUserDescriptor(defaultValue: String): BluetoothGattDescriptor {
        val descriptor = BluetoothGattDescriptor(
                GATTDefinitions.SIG_USER_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        try {
            descriptor.value = defaultValue.toByteArray(charset("UTF-8"))
        } finally {
            return descriptor
        }
    }

    fun getBluetoothGattService(): BluetoothGattService {
        return lockerService
    }
}
package com.smartlock.ble.service

import android.app.Service
import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import com.safframework.log.L
import com.smartlock.ble.R
import com.smartlock.ble.profile.Unlock
import java.util.*

class BLEService : Service() {

    private val TAG = BLEService::class.qualifiedName

    private var binder = LocalBinder()

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var deviceAddress: String = ""
    private var gattServer: BluetoothGattServer? = null
    private lateinit var profile: Unlock
    private var advSettings: AdvertiseSettings? = null
    private var advData: AdvertiseData? = null
    private var advScanResponse: AdvertiseData? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var alarm = false
    private var btServerDevices: HashSet<BluetoothDevice> = HashSet()
    private var btClientDevices: HashSet<BluetoothDevice> = HashSet()

    var connectionState = STATE_DISCONNECTED
    val ACTION_GATT_CONNECTED = "ACTION_GATT_CONNECTED"
    val ACTION_GATT_DISCONNECTED = "ACTION_GATT_DISCONNECTED"
    val ACTION_GATT_SERVICES_DISCOVERED = "ACTION_GATT_SERVICES_DISCOVERED"
    val ACTION_DATA_AVAILABLE = "ACTION_DATA_AVAILABLE"
    val EXTRA_DATA = "EXTRA_DATA"

    inner class LocalBinder : Binder() {
        fun getService(): BLEService {
            return this@BLEService
        }
    }

    override fun onBind(intent: Intent): IBinder {
        L.w(TAG, "onBind")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        L.w(TAG, "onUnbind")
        stop()
        return super.onUnbind(intent)
    }

    private val advListener = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            L.w("Not broadcasting: $errorCode")
            val statusText: Int
            when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> {
                    statusText = R.string.status_advertising
                    L.w(resources.getString(statusText))
                }
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> {
                    statusText = R.string.status_advDataTooLarge
                    L.w(resources.getString(statusText))
                }
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                    statusText = R.string.status_advFeatureUnsupported
                    L.w(resources.getString(statusText))
                }
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> {
                    statusText = R.string.status_advInternalError
                    L.w(resources.getString(statusText))
                }
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                    statusText = R.string.status_advTooManyAdvertisers
                    L.w(resources.getString(statusText))
                }
                else -> {
                    statusText = R.string.status_notAdvertising
                    L.w("Unhandled error: $errorCode\n" + resources.getString(statusText))
                }
            }
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            L.w("Broadcasting")
        }
    }

    private val gattServerListener = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    btServerDevices!!.add(device)
                    L.i("Connected to device: " + device.address)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    btServerDevices!!.remove(device)
                    L.i("Disconnected from device")
                }
            } else {
                btServerDevices!!.remove(device)
                L.e("Error when connecting: $status")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            L.d("Device tried to read characteristic: " + characteristic.uuid)
            L.d("Value: " + Arrays.toString(characteristic.value))
            if (offset != 0) {
                gattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        /* No need to respond with a value */ null)/* value (optional) */
                return
            }
            gattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            L.i("Notification sent. Status: $status")
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
                                                  offset: Int, value: ByteArray) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value)
            L.i("Characteristic Write request: " + Arrays.toString(value))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int,
                                             offset: Int, descriptor: BluetoothGattDescriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            L.d("Device tried to read descriptor: " + descriptor.uuid)
            L.d("Value: " + Arrays.toString(descriptor.value))
            if (offset != 0) {
                gattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)/* value (optional) */
                return
            }
            gattServer!!.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    descriptor.value)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
                                              offset: Int,
                                              value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
                    offset, value)
            L.i("Descriptor Write Request " + descriptor.uuid + " " + Arrays.toString(value))
            var status = BluetoothGatt.GATT_SUCCESS
            if (descriptor.uuid === GATTDefinitions.SIG_CLIENT_UUID) {
                val characteristic = descriptor.characteristic
                val supportsNotifications = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val supportsIndications = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

                if (!(supportsNotifications || supportsIndications)) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                } else if (value.size != 2) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS
                    descriptor.value = value
                } else if (supportsNotifications && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS
                    descriptor.value = value
                } else if (supportsIndications && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS
                    descriptor.value = value
                } else {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
            } else {
                status = BluetoothGatt.GATT_SUCCESS
                descriptor.value = value
            }
            if (responseNeeded) {
                gattServer!!.sendResponse(device, requestId, status,
                        /* No need to respond with offset */ 0, null)
            }
        }
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val gattClientListener = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val intentAction: String
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED
                connectionState = STATE_CONNECTED
                broadcastUpdate(intentAction)
                L.w(TAG, "Attempting to start service discovery:" + bluetoothGatt?.discoverServices())

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED
                connectionState = STATE_DISCONNECTED
                broadcastUpdate(intentAction)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            } else {
                L.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic,
                                          status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }
    }

    private fun stop(){
        for (device in btServerDevices){
//            device.
        }
    }

    private fun broadcastUpdate(action: String) {
        if (action.equals(ACTION_GATT_CONNECTED)) L.w(TAG, "broadcastUpdate " + action)
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(action: String,
                                characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)

        val data = characteristic.value
        if (data != null && data.size > 0) {
            val stringBuilder = StringBuilder(data.size)
            for (byteChar in data)
                stringBuilder.append(String.format("%02X ", byteChar))
            intent.putExtra(EXTRA_DATA, String(data) + "\n" + stringBuilder.toString())
        }
        sendBroadcast(intent)
    }


    fun initialize(): Boolean {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (bluetoothManager == null) {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.")
                return false
            }
        }

        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }

        gattServer = bluetoothManager!!.openGattServer(this, gattServerListener)
        profile = Unlock(this)
        advSettings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
        advData = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .addServiceUuid(ParcelUuid(Unlock.SMART_LOCKER_SERVICE_UUID))
                .build()
        advScanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
        return true
    }


    fun startAdvertise() {
        L.w("startAdvertise")
        if (gattServer == null) {
            L.w("gattServer is null")
            return
        }
        if (gattServer!!.getService(Unlock.SMART_LOCKER_SERVICE_UUID) == null) {
            gattServer!!.addService(profile.getBluetoothGattService())

            if (bluetoothAdapter!!.isMultipleAdvertisementSupported()) {
                advertiser = bluetoothAdapter!!.getBluetoothLeAdvertiser()
                advertiser!!.startAdvertising(advSettings, advData, advScanResponse, advListener)
            }
        }
    }

    fun stopAdvertise() {
        L.w("stopAdvertise")
        if (gattServer == null) {
            L.w("gattServer is null")
            return
        }
        if (advertiser != null) {
            advertiser!!.stopAdvertising(advListener)
            advertiser = null
        }
        if (gattServer!!.getService(Unlock.SMART_LOCKER_SERVICE_UUID) != null) {
            gattServer!!.removeService(profile.getBluetoothGattService())
            gattServer!!.close()
        }
    }

    fun connect(address: String?): Boolean {
        if (bluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.")
            return false
        }

        // Previously connected device.  Try to reconnect.
        if (address == deviceAddress && bluetoothGatt != null) {
//            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.")
            return if (bluetoothGatt?.connect() == true) {
                connectionState = STATE_CONNECTING
                true
            } else {
                false
            }
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.")
            return false
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        bluetoothGatt = device.connectGatt(this, false, gattClientListener)
//        Log.d(TAG, "Trying to create a new connection.")
        deviceAddress = address
        connectionState = STATE_CONNECTING
        btClientDevices.add(device)
        return true
    }

    fun disconnect() {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        bluetoothGatt?.disconnect()
    }

    fun disconnectFromDevices() {
        L.d("Disconnecting devices...")
        for (device in bluetoothManager!!.getConnectedDevices(BluetoothGattServer.GATT)) {
            L.d("Devices: " + device.getAddress() + " " + device.getName())
            gattServer!!.cancelConnection(device)
        }
    }

    fun close() {
        if (bluetoothGatt == null) {
            return
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        bluetoothGatt?.readCharacteristic(characteristic)
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }

        if (!alarm) {
            characteristic.value = byteArrayOf(1)
            alarm = true
        } else {
            characteristic.value = byteArrayOf(0)
            alarm = false
        }
        bluetoothGatt?.writeCharacteristic(characteristic)
    }

    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic,
                                      enabled: Boolean) {
        if (bluetoothAdapter == null || bluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        bluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
    }

    fun getSupportedGattServices(): List<BluetoothGattService>? {
        return bluetoothGatt?.getServices()

    }
}

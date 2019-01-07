package com.smartlock.example

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.safframework.log.L
import com.smartlock.ble.profile.Unlock
import com.smartlock.ble.service.BLEService

class BeaconActivity : AppCompatActivity() {
    private val TAG = "SmartLock"
    private val REQUEST_ENABLE_BT = 1
    private val REQUEST_COARSE_LOCATION = 2

    private var message: TextView? = null
    private var unlockButton: ImageView? = null

    private var btAvailable = false
    private var bleScanning = false
    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var bleService: BLEService? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleSettings: ScanSettings? = null
    private var password: String = ""
    private var btDevice: ScanResult? = null

    private lateinit var bleFilters: ArrayList<ScanFilter>
    private lateinit var handler: Handler
    private lateinit var activity: BeaconActivity

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            bleService = (service as BLEService.LocalBinder).getService()
            L.e("onServiceConnected")
            if (bleService?.initialize() == false) {
                L.e("Unable to initialize Bluetooth")
                finish()
            }
            bleService!!.startAdvertise()
            if(btDevice != null) bleService!!.connect(btDevice!!.device.address)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            L.e("onServiceDisconnected")
            bleService = null
        }
    }

    private val stopRunnable = Runnable { scan() }

    private val scanCallback = object : ScanCallback() {
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            if(results!!.size > 0){
                handler.removeCallbacks(stopRunnable)
                scan()
                val intent = Intent(activity, BLEService::class.java)
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
                btDevice = results[0]
                L.i("scanCallback:" + results.toString())
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            L.i("onScanFailed:" + errorCode)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            L.i("onScanResult:" + callbackType + " " + result.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beacon)
        message = findViewById(R.id.message)
        unlockButton = findViewById(R.id.unlock)
        handler = Handler()
        // check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_COARSE_LOCATION)
            }
        }
        activity = this
        // setup bluetooth
        btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        btAdapter = btManager?.adapter
        if (btAdapter == null) {
            L.d("Cannot find bluetooth adapter")
        } else {
            bleScanner = btAdapter?.bluetoothLeScanner
            if (bleScanner != null) {
                bleSettings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(100)
                        .build()
                bleFilters = ArrayList<ScanFilter>()
                bleFilters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(Unlock.SMART_LOCKER_SERVICE_UUID)).build())

                unlockButton!!.isActivated = false
                unlockButton!!.isEnabled = true
                unlockButton!!.setOnClickListener {
                    changeButton(scan())
                }
                if (btAdapter?.isEnabled != true) {
                    btAvailable = false
                    val enableBTIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    startActivityForResult(enableBTIntent, REQUEST_ENABLE_BT)
                } else {
                    btAvailable = true
                }
            }

        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult")
        when (requestCode) {
            REQUEST_ENABLE_BT -> handleRequestEnableBluetooth(resultCode)
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_COARSE_LOCATION -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Scan should now succeed")
                }
            }
            else -> Log.d(TAG, "Scan will be not effective")
        }
    }

    private fun handleRequestEnableBluetooth(resultCode: Int) {
        Log.d(TAG, "Bluetooth enable request: result code $resultCode")
        if (resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Bluetooth working")
            btAvailable = true
        } else {
            Log.d(TAG, "Bluetooth not working")
        }
    }

    private fun scan(): Boolean {
        if (btAvailable) {
            if (!bleScanning) {
                L.d("Start LE scan, bindService")

                val intent = Intent(this, BLEService::class.java)
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)

                bleScanner!!.startScan(bleFilters,bleSettings,scanCallback)

                bleScanning = true
//                handler.postDelayed(stopRunnable, 10000)

            } else {
                Log.d(TAG, "Stop LE scan, unbindService")

                bleScanner!!.stopScan(scanCallback)

                bleScanning = false

                if (bleService != null) {
                    bleService!!.stopAdvertise()
                    unbindService(serviceConnection)
                }
            }
        }
        return bleScanning
    }

    private fun changeButton(isOpen: Boolean) {
        unlockButton!!.isActivated = isOpen
        if (isOpen) {
            message!!.visibility = View.VISIBLE
            message!!.text = message!!.text.toString() + "啟動藍牙..." + "\n"
        } else {
            message!!.visibility = View.INVISIBLE
            message!!.text = ""
        }
    }

}


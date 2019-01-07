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
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.RelativeLayout
import com.safframework.log.L
import com.smartlock.ble.profile.Unlock
import com.smartlock.ble.service.BLEService


class LockActivity : AppCompatActivity() {
    private val TAG = "SmartLock"
    private val REQUEST_ENABLE_BT = 1
    private val REQUEST_COARSE_LOCATION = 2
    private var keypad: RelativeLayout? = null
    private var keypadCover: RelativeLayout? = null


    private var slideDown: Animation? = null
    private var slideUp: Animation? = null

    private var btAvailable = false
    private var bleScanning = false
    private var btManager: BluetoothManager? = null
    private var btAdapter: BluetoothAdapter? = null
    private var bleService: BLEService? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleSettings: ScanSettings? = null
    private var password: String = ""

    private lateinit var bleFilters: ArrayList<ScanFilter>
    private lateinit var handler: Handler
    private lateinit var activity: LockActivity
    private lateinit var btDevice: ScanResult

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            bleService = (service as BLEService.LocalBinder).getService()
            L.e("onServiceConnected")
            if (bleService?.initialize() == false) {
                L.e("Unable to initialize Bluetooth")
                finish()
            }
            bleService!!.startAdvertise()
            bleService!!.connect(btDevice.device.address)
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
                L.i("onBatchScanResults:" + results.toString())
            }
       }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            L.i("onScanFailed:" + errorCode)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            if(result != null && bleScanning){
                handler.removeCallbacks(stopRunnable)
                scan()
                val intent = Intent(activity, BLEService::class.java)
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
                btDevice = result
                L.i("onScanResult:" + result.toString())
            }
        }
    }

    private val aniListener = object : Animation.AnimationListener {
        override fun onAnimationStart(animation: Animation?) {
            L.i("onAnimationStart")
        }

        override fun onAnimationRepeat(animation: Animation?) {
            L.i("onAnimationRepeat")
        }

        override fun onAnimationEnd(animation: Animation?) {
            if (bleScanning) {
                L.i("hide")
                keypadCover!!.visibility = View.GONE

            } else {
                L.i("show")
                keypadCover!!.visibility = View.VISIBLE

            }
        }
    }

    private val keypadListener = object : View.OnClickListener {
        override fun onClick(v: View?) {
            L.w("> id:" + v!!.id + " tag:" + v!!.tag + " " + password)

            when (v!!.tag) {
                resources.getString(R.string.k0)
                    , resources.getString(R.string.k1)
                    , resources.getString(R.string.k2)
                    , resources.getString(R.string.k3)
                    , resources.getString(R.string.k4)
                    , resources.getString(R.string.k5)
                    , resources.getString(R.string.k6)
                    , resources.getString(R.string.k7)
                    , resources.getString(R.string.k8)
                    , resources.getString(R.string.k9)
                    , resources.getString(R.string.kasterisk)
                    , resources.getString(R.string.kpound)

                -> {
                    password += v!!.tag
                    handler.removeCallbacks(stopRunnable)
                    handler.postDelayed(stopRunnable, 10000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        keypad = findViewById(R.id.keypad)
        findViewById<Button>(R.id.k0).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k1).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k2).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k3).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k4).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k5).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k6).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k7).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k8).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.k9).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.kasterisk).setOnClickListener(keypadListener)
        findViewById<Button>(R.id.kpound).setOnClickListener(keypadListener)
        keypadCover = findViewById(R.id.keypadCover)

        handler = Handler()
        slideDown = AnimationUtils.loadAnimation(applicationContext,
                R.anim.slide_down)
        slideUp = AnimationUtils.loadAnimation(applicationContext,
                R.anim.slide_up)
        slideDown!!.setAnimationListener(aniListener)
        slideUp!!.setAnimationListener(aniListener)

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
            Log.d(TAG, "Cannot find bluetooth adapter")
        } else {
            bleScanner = btAdapter?.bluetoothLeScanner
            if (bleScanner != null) {
                bleSettings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
//                        .setReportDelay(100)
                        .build()
                bleFilters = ArrayList<ScanFilter>()
                bleFilters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(Unlock.SMART_LOCKER_SERVICE_UUID)).build())

                keypadCover!!.setOnTouchListener(View.OnTouchListener { view, motionEvent ->
                    if (!bleScanning) {
                        scan()
                        view.startAnimation(slideDown)
                    }

                    return@OnTouchListener false
                })
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

    private fun scan() {
        if (btAvailable) {
            if (!bleScanning) {
                L.d("Start LE scan, bindService")

//                val intent = Intent(this, BLEService::class.java)
//                bindService(intent, serviceConnection, BIND_AUTO_CREATE)

                bleScanner!!.startScan(bleFilters,bleSettings,scanCallback)

                bleScanning = true
                handler.postDelayed(stopRunnable, 10000)

            } else {
                Log.d(TAG, "Stop LE scan, unbindService")

                bleScanner!!.stopScan(scanCallback)

                bleScanning = false
                keypadCover!!.startAnimation(slideUp)

//                if (bleService != null) {
//                    bleService!!.stopAdvertise()
//                    unbindService(serviceConnection)
//                }
            }
        }
    }

    override fun onPause() {
        if (bleScanning) {
            bleScanner!!.stopScan(scanCallback)
            bleScanning = false
            keypadCover!!.startAnimation(slideUp)
        }
        super.onPause()
    }
}

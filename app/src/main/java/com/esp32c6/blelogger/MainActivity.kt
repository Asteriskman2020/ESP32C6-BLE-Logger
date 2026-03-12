package com.esp32c6.blelogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), BleManager.BleCallback {

    private lateinit var bleManager: BleManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Log
    private val logLines = ArrayDeque<String>(51)
    private val maxLogLines = 50

    // Scan results
    private val foundDevices = mutableListOf<BluetoothDevice>()
    private val foundDeviceRssi = mutableMapOf<String, Int>()

    // Views
    private lateinit var toolbar: Toolbar

    // Connection card
    private lateinit var viewConnectionDot: View
    private lateinit var tvDeviceName: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    // Sensor card
    private lateinit var tvTempAht: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvTempBmp: TextView
    private lateinit var tvPressure: TextView

    // Buffer card
    private lateinit var tvBufferCount: TextView
    private lateinit var progressBuffer: ProgressBar

    // Message card
    private lateinit var tvMessageStatus: TextView
    private lateinit var btnClear: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnRead: MaterialButton
    private lateinit var btnClrBuf: MaterialButton

    // Log card
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnClearLog: MaterialButton

    // Permission request launchers
    private val requestBluetoothPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            addLog("Permissions granted")
            checkBluetoothEnabled()
        } else {
            addLog("Some permissions denied. BLE may not work correctly.")
            Toast.makeText(this, "BLE permissions are required for this app", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            addLog("Bluetooth enabled")
        } else {
            addLog("Bluetooth not enabled")
            Toast.makeText(this, "Bluetooth must be enabled to use this app", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bleManager = BleManager(this)
        bleManager.callback = this

        setupViews()
        setupClickListeners()
        requestRequiredPermissions()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        // Connection card
        viewConnectionDot = findViewById(R.id.viewConnectionDot)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        btnScan = findViewById(R.id.btnScan)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // Sensor card
        tvTempAht = findViewById(R.id.tvTempAht)
        tvHumidity = findViewById(R.id.tvHumidity)
        tvTempBmp = findViewById(R.id.tvTempBmp)
        tvPressure = findViewById(R.id.tvPressure)

        // Buffer card
        tvBufferCount = findViewById(R.id.tvBufferCount)
        progressBuffer = findViewById(R.id.progressBuffer)
        progressBuffer.max = 20
        progressBuffer.progress = 0

        // Message card
        tvMessageStatus = findViewById(R.id.tvMessageStatus)
        btnClear = findViewById(R.id.btnClear)
        btnReset = findViewById(R.id.btnReset)
        btnRead = findViewById(R.id.btnRead)
        btnClrBuf = findViewById(R.id.btnClrBuf)

        // Log card
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnClearLog = findViewById(R.id.btnClearLog)

        // Initial state
        updateConnectionState(false, "")
        updateSensorDisplay("--", "--", "--", "--")
        updateBufferDisplay(0)
        updateMessageStatus("--")
    }

    private fun setupClickListeners() {
        btnScan.setOnClickListener {
            if (bleManager.isScanning()) {
                bleManager.stopScan()
                setScanButtonState(false)
            } else {
                foundDevices.clear()
                foundDeviceRssi.clear()
                bleManager.startScan()
                setScanButtonState(true)
                // Show dialog after a short delay to collect results
                mainHandler.postDelayed({
                    if (bleManager.isScanning() || foundDevices.isNotEmpty()) {
                        showDeviceSelectionDialog()
                    }
                }, 3000)
            }
        }

        btnDisconnect.setOnClickListener {
            bleManager.disconnect()
        }

        btnClear.setOnClickListener { bleManager.sendCommand("CLEAR") }
        btnReset.setOnClickListener { bleManager.sendCommand("RESET") }
        btnRead.setOnClickListener { bleManager.sendCommand("READ") }
        btnClrBuf.setOnClickListener { bleManager.sendCommand("CLRBUF") }

        btnClearLog.setOnClickListener {
            logLines.clear()
            tvLog.text = ""
        }
    }

    // ---------- Permission & Bluetooth ----------

    private fun requestRequiredPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestBluetoothPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            checkBluetoothEnabled()
        }
    }

    private fun checkBluetoothEnabled() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Toast.makeText(this, "This device does not support Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            addLog("Bluetooth ready. Tap SCAN to find devices.")
        }
    }

    // ---------- Device Selection Dialog ----------

    private fun showDeviceSelectionDialog() {
        runOnUiThread {
            val devices = foundDevices.toList()
            if (devices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("No Devices Found")
                    .setMessage("No ESP32-C6 BLE devices were found nearby.\n\nMake sure the device is powered on and advertising.")
                    .setPositiveButton("OK") { _, _ ->
                        bleManager.stopScan()
                        setScanButtonState(false)
                    }
                    .setNegativeButton("Keep Scanning") { _, _ -> }
                    .show()
                return@runOnUiThread
            }

            val deviceNames = devices.map { device ->
                val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                        PackageManager.PERMISSION_GRANTED) device.name ?: device.address else device.address
                } else {
                    device.name ?: device.address
                }
                val rssi = foundDeviceRssi[device.address] ?: -100
                "$name\n${device.address}  RSSI: ${rssi}dBm"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Select BLE Device")
                .setItems(deviceNames) { _, index ->
                    bleManager.stopScan()
                    setScanButtonState(false)
                    bleManager.connect(devices[index])
                }
                .setNegativeButton("Cancel") { _, _ ->
                    bleManager.stopScan()
                    setScanButtonState(false)
                }
                .show()
        }
    }

    // ---------- BleManager.BleCallback ----------

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            updateConnectionState(true, deviceName)
            addLog("Connected: $deviceName")
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            updateConnectionState(false, "")
            updateSensorDisplay("--", "--", "--", "--")
            updateBufferDisplay(0)
            updateMessageStatus("--")
            setScanButtonState(false)
            addLog("Disconnected")
        }
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        runOnUiThread {
            if (!foundDevices.any { it.address == device.address }) {
                foundDevices.add(device)
            }
            foundDeviceRssi[device.address] = rssi

            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED) device.name ?: device.address else device.address
            } else {
                device.name ?: device.address
            }

            // Auto-connect to known device
            if (name == BleManager.TARGET_DEVICE_NAME) {
                addLog("Found target device: $name. Auto-connecting...")
                bleManager.stopScan()
                setScanButtonState(false)
                bleManager.connect(device)
            }
        }
    }

    override fun onScanStopped() {
        runOnUiThread {
            setScanButtonState(false)
        }
    }

    override fun onSensorUpdate(tempAht: Float, humidity: Float, pressure: Float, tempBmp: Float) {
        runOnUiThread {
            updateSensorDisplay(
                "%.1f".format(tempAht),
                "%.1f".format(humidity),
                "%.1f".format(pressure),
                "%.1f".format(tempBmp)
            )
        }
    }

    override fun onMessageUpdate(msg: String) {
        runOnUiThread {
            // Message char shows content; we show it in log only
        }
    }

    override fun onStatusUpdate(status: String) {
        runOnUiThread {
            updateMessageStatus(status)
        }
    }

    override fun onCountUpdate(count: Int) {
        runOnUiThread {
            updateBufferDisplay(count)
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            addLog(msg)
        }
    }

    // ---------- UI Update Helpers ----------

    private fun updateConnectionState(connected: Boolean, deviceName: String) {
        if (connected) {
            viewConnectionDot.setBackgroundResource(R.drawable.dot_connected)
            tvDeviceName.text = deviceName
            btnDisconnect.isEnabled = true
            btnClear.isEnabled = true
            btnReset.isEnabled = true
            btnRead.isEnabled = true
            btnClrBuf.isEnabled = true
        } else {
            viewConnectionDot.setBackgroundResource(R.drawable.dot_disconnected)
            tvDeviceName.text = getString(R.string.not_connected)
            btnDisconnect.isEnabled = false
            btnClear.isEnabled = false
            btnReset.isEnabled = false
            btnRead.isEnabled = false
            btnClrBuf.isEnabled = false
        }
    }

    private fun setScanButtonState(scanning: Boolean) {
        if (scanning) {
            btnScan.text = getString(R.string.stop_scan)
            viewConnectionDot.setBackgroundResource(R.drawable.dot_scanning)
        } else {
            btnScan.text = getString(R.string.scan)
            if (!bleManager.isConnected()) {
                viewConnectionDot.setBackgroundResource(R.drawable.dot_disconnected)
            }
        }
    }

    private fun updateSensorDisplay(tempAht: String, humidity: String, pressure: String, tempBmp: String) {
        tvTempAht.text = if (tempAht == "--") "--" else "$tempAht °C"
        tvHumidity.text = if (humidity == "--") "--" else "$humidity %"
        tvTempBmp.text = if (tempBmp == "--") "--" else "$tempBmp °C"
        tvPressure.text = if (pressure == "--") "--" else "$pressure hPa"
    }

    private fun updateBufferDisplay(count: Int) {
        tvBufferCount.text = getString(R.string.buffer_count, count, 20)
        progressBuffer.progress = count.coerceIn(0, 20)
    }

    private fun updateMessageStatus(status: String) {
        tvMessageStatus.text = status
        when (status.uppercase()) {
            "READY" -> {
                tvMessageStatus.setBackgroundResource(R.drawable.badge_ready)
                tvMessageStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            "EMPTY" -> {
                tvMessageStatus.setBackgroundResource(R.drawable.badge_empty)
                tvMessageStatus.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            }
            else -> {
                tvMessageStatus.setBackgroundResource(R.drawable.badge_default)
                tvMessageStatus.setTextColor(ContextCompat.getColor(this, R.color.textPrimary))
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message"

        if (logLines.size >= maxLogLines) {
            logLines.removeFirst()
        }
        logLines.addLast(line)

        tvLog.text = logLines.joinToString("\n")

        // Auto-scroll to bottom
        mainHandler.post {
            scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ---------- Lifecycle ----------

    override fun onPause() {
        super.onPause()
        // Stop scanning when app is paused
        if (bleManager.isScanning()) {
            bleManager.stopScan()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
    }
}

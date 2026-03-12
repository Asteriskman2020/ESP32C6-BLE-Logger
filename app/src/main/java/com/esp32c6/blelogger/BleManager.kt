package com.esp32c6.blelogger

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.*

data class SensorData(
    val tempAht: Float,
    val humidity: Float,
    val pressure: Float,
    val tempBmp: Float
)

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        const val TARGET_DEVICE_NAME = "ESP32-C6 BLE"

        val SERVICE_UUID: UUID = UUID.fromString("ab000001-0000-1000-8000-00805f9b34fb")
        val CHAR_MESSAGE_UUID: UUID = UUID.fromString("ab000002-0000-1000-8000-00805f9b34fb")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("ab000003-0000-1000-8000-00805f9b34fb")
        val CHAR_STATUS_UUID: UUID = UUID.fromString("ab000004-0000-1000-8000-00805f9b34fb")
        val CHAR_SENSOR_UUID: UUID = UUID.fromString("ab000005-0000-1000-8000-00805f9b34fb")
        val CHAR_COUNT_UUID: UUID = UUID.fromString("ab000006-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val GATT_OP_DELAY_MS = 300L
        private const val SCAN_PERIOD_MS = 15000L
    }

    interface BleCallback {
        fun onConnected(deviceName: String)
        fun onDisconnected()
        fun onScanResult(device: BluetoothDevice, rssi: Int)
        fun onScanStopped()
        fun onSensorUpdate(tempAht: Float, humidity: Float, pressure: Float, tempBmp: Float)
        fun onMessageUpdate(msg: String)
        fun onStatusUpdate(status: String)
        fun onCountUpdate(count: Int)
        fun onLog(msg: String)
    }

    var callback: BleCallback? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var isConnected = false
    private var isScanning = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gattOperationQueue: Queue<Runnable> = LinkedList()
    private var isGattOperationInProgress = false

    // Characteristics cache
    private var charMessage: BluetoothGattCharacteristic? = null
    private var charCommand: BluetoothGattCharacteristic? = null
    private var charStatus: BluetoothGattCharacteristic? = null
    private var charSensor: BluetoothGattCharacteristic? = null
    private var charCount: BluetoothGattCharacteristic? = null

    // ---------- Permission helpers ----------

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------- BLE Scanning ----------

    fun startScan() {
        if (!hasBluetoothPermissions()) {
            callback?.onLog("Missing BLE permissions")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            callback?.onLog("Bluetooth is not enabled")
            return
        }
        if (isScanning) return

        isScanning = true
        callback?.onLog("Scanning for BLE devices...")

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(filters, settings, scanCallback)

        // Auto-stop after SCAN_PERIOD_MS
        mainHandler.postDelayed({
            if (isScanning) stopScan()
        }, SCAN_PERIOD_MS)
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        if (hasBluetoothPermissions()) {
            bleScanner?.stopScan(scanCallback)
        }
        callback?.onLog("Scan stopped")
        callback?.onScanStopped()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED) device.name else null
            } else {
                device.name
            }
            val rssi = result.rssi
            Log.d(TAG, "Found device: $name (${device.address}) RSSI: $rssi")
            callback?.onScanResult(device, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            callback?.onLog("Scan failed with error: $errorCode")
            callback?.onScanStopped()
        }
    }

    // ---------- BLE Connection ----------

    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            callback?.onLog("Missing BLE permissions")
            return
        }
        if (isConnected) {
            disconnect()
        }
        stopScan()

        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED) device.name ?: device.address else device.address
        } else {
            device.name ?: device.address
        }

        callback?.onLog("Connecting to $deviceName...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        if (!hasBluetoothPermissions()) return
        isConnected = false
        clearCharacteristics()
        gattOperationQueue.clear()
        isGattOperationInProgress = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        callback?.onLog("Disconnected")
        callback?.onDisconnected()
    }

    private fun clearCharacteristics() {
        charMessage = null
        charCommand = null
        charStatus = null
        charSensor = null
        charCount = null
    }

    // ---------- GATT Callbacks ----------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasBluetoothPermissions()) return

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    val deviceName = gatt.device?.name ?: gatt.device?.address ?: "Unknown"
                    Log.d(TAG, "Connected to $deviceName")
                    callback?.onLog("Connected to $deviceName. Discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected (status=$status)")
                    val wasConnected = isConnected
                    isConnected = false
                    clearCharacteristics()
                    gattOperationQueue.clear()
                    isGattOperationInProgress = false
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    if (wasConnected) {
                        mainHandler.post {
                            callback?.onLog("Disconnected from device")
                            callback?.onDisconnected()
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback?.onLog("Service discovery failed: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                callback?.onLog("ESP32-C6 BLE service not found!")
                return
            }

            charMessage = service.getCharacteristic(CHAR_MESSAGE_UUID)
            charCommand = service.getCharacteristic(CHAR_COMMAND_UUID)
            charStatus = service.getCharacteristic(CHAR_STATUS_UUID)
            charSensor = service.getCharacteristic(CHAR_SENSOR_UUID)
            charCount = service.getCharacteristic(CHAR_COUNT_UUID)

            isConnected = true
            val deviceName = if (hasBluetoothPermissions()) {
                gatt.device?.name ?: gatt.device?.address ?: "Unknown"
            } else {
                gatt.device?.address ?: "Unknown"
            }

            mainHandler.post {
                callback?.onLog("Services discovered. Setting up notifications...")
                callback?.onConnected(deviceName)
            }

            // Queue GATT operations sequentially
            queueNotificationSetup(gatt, charMessage, "Message")
            queueNotificationSetup(gatt, charStatus, "Status")
            queueNotificationSetup(gatt, charSensor, "Sensor")
            queueReadCharacteristic(gatt, charCount, "Count")

            processNextGattOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            val stringValue = String(value, Charsets.UTF_8)
            handleCharacteristicValue(characteristic.uuid, stringValue)
        }

        // For API >= 33
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val stringValue = String(value, Charsets.UTF_8)
            handleCharacteristicValue(characteristic.uuid, stringValue)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value ?: return
                val stringValue = String(value, Charsets.UTF_8)
                handleCharacteristicValue(characteristic.uuid, stringValue)
            }
            isGattOperationInProgress = false
            processNextGattOperation()
        }

        // For API >= 33
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val stringValue = String(value, Charsets.UTF_8)
                handleCharacteristicValue(characteristic.uuid, stringValue)
            }
            isGattOperationInProgress = false
            processNextGattOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status")
            }
            isGattOperationInProgress = false
            processNextGattOperation()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: ${descriptor.uuid} status=$status")
            }
            isGattOperationInProgress = false
            processNextGattOperation()
        }
    }

    // ---------- GATT Operation Queue ----------

    private fun queueNotificationSetup(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        label: String
    ) {
        if (characteristic == null) {
            callback?.onLog("$label characteristic not found")
            return
        }
        gattOperationQueue.add(Runnable {
            enableNotification(gatt, characteristic, label)
        })
    }

    private fun queueReadCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic?,
        label: String
    ) {
        if (characteristic == null) {
            callback?.onLog("$label characteristic not found")
            return
        }
        gattOperationQueue.add(Runnable {
            if (!hasBluetoothPermissions()) {
                isGattOperationInProgress = false
                processNextGattOperation()
                return@Runnable
            }
            Log.d(TAG, "Reading $label characteristic")
            val success = gatt.readCharacteristic(characteristic)
            if (!success) {
                isGattOperationInProgress = false
                processNextGattOperation()
            }
        })
    }

    private fun processNextGattOperation() {
        if (isGattOperationInProgress) return
        val op = gattOperationQueue.poll() ?: return
        isGattOperationInProgress = true
        mainHandler.postDelayed({
            op.run()
        }, GATT_OP_DELAY_MS)
    }

    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        label: String
    ) {
        if (!hasBluetoothPermissions()) {
            isGattOperationInProgress = false
            processNextGattOperation()
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD descriptor not found for $label")
            isGattOperationInProgress = false
            processNextGattOperation()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (result != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "Failed to write CCCD for $label")
                isGattOperationInProgress = false
                processNextGattOperation()
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val success = gatt.writeDescriptor(descriptor)
            if (!success) {
                Log.e(TAG, "Failed to write CCCD for $label")
                isGattOperationInProgress = false
                processNextGattOperation()
            }
        }
        Log.d(TAG, "Enabling notification for $label")
        callback?.onLog("Enabled notification: $label")
    }

    // ---------- Value Parsing ----------

    private fun handleCharacteristicValue(uuid: UUID, value: String) {
        when (uuid) {
            CHAR_MESSAGE_UUID -> {
                mainHandler.post {
                    callback?.onMessageUpdate(value)
                    callback?.onLog("MSG: $value")
                }
            }
            CHAR_STATUS_UUID -> {
                mainHandler.post {
                    callback?.onStatusUpdate(value)
                    callback?.onLog("STATUS: $value")
                }
            }
            CHAR_SENSOR_UUID -> {
                val sensor = parseSensorData(value)
                if (sensor != null) {
                    mainHandler.post {
                        callback?.onSensorUpdate(sensor.tempAht, sensor.humidity, sensor.pressure, sensor.tempBmp)
                        callback?.onLog("SENSOR: T=${sensor.tempAht} H=${sensor.humidity} P=${sensor.pressure} Tb=${sensor.tempBmp}")
                    }
                } else {
                    mainHandler.post {
                        callback?.onLog("SENSOR raw: $value")
                    }
                }
            }
            CHAR_COUNT_UUID -> {
                val count = value.trim().toIntOrNull() ?: 0
                mainHandler.post {
                    callback?.onCountUpdate(count)
                    callback?.onLog("COUNT: $count")
                }
            }
        }
    }

    /**
     * Parse sensor string format: "T:25.3 H:60.2 P:1013.5 Tb:24.8"
     */
    private fun parseSensorData(raw: String): SensorData? {
        return try {
            val map = mutableMapOf<String, Float>()
            val pattern = Regex("""(\w+):([\d.+-]+)""")
            pattern.findAll(raw).forEach { match ->
                val key = match.groupValues[1]
                val v = match.groupValues[2].toFloatOrNull()
                if (v != null) map[key] = v
            }
            val tempAht = map["T"] ?: return null
            val humidity = map["H"] ?: return null
            val pressure = map["P"] ?: return null
            val tempBmp = map["Tb"] ?: return null
            SensorData(tempAht, humidity, pressure, tempBmp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse sensor data: $raw", e)
            null
        }
    }

    // ---------- Command Sending ----------

    fun sendCommand(command: String) {
        if (!isConnected) {
            callback?.onLog("Not connected")
            return
        }
        val characteristic = charCommand
        if (characteristic == null) {
            callback?.onLog("Command characteristic not found")
            return
        }
        if (!hasBluetoothPermissions()) {
            callback?.onLog("Missing BLE permissions")
            return
        }
        val gatt = bluetoothGatt ?: return
        gattOperationQueue.add(Runnable {
            val bytes = command.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (result != BluetoothStatusCodes.SUCCESS) {
                    callback?.onLog("Failed to send command: $command")
                    isGattOperationInProgress = false
                    processNextGattOperation()
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                val success = gatt.writeCharacteristic(characteristic)
                if (!success) {
                    callback?.onLog("Failed to send command: $command")
                    isGattOperationInProgress = false
                    processNextGattOperation()
                }
            }
            callback?.onLog("CMD sent: $command")
        })
        processNextGattOperation()
    }

    fun readCount() {
        if (!isConnected) return
        val gatt = bluetoothGatt ?: return
        queueReadCharacteristic(gatt, charCount, "Count")
        processNextGattOperation()
    }

    fun isConnected(): Boolean = isConnected
    fun isScanning(): Boolean = isScanning
}

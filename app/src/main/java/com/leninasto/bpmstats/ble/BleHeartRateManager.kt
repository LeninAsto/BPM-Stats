package com.leninasto.bpmstats.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class BleDeviceInfo(
    val name: String,
    val address: String,
    val rssi: Int,
    val isHeartRateSensor: Boolean,
)

class BleHeartRateManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate

    private val _connectionState = MutableStateFlow("Desconectado")
    val connectionState: StateFlow<String> = _connectionState

    private val _discoveredDevices = MutableStateFlow<List<BleDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDeviceInfo>> = _discoveredDevices

    private val _connectedDevice = MutableStateFlow<BleDeviceInfo?>(null)
    val connectedDevice: StateFlow<BleDeviceInfo?> = _connectedDevice

    private val _signalRssi = MutableStateFlow<Int?>(null)
    val signalRssi: StateFlow<Int?> = _signalRssi

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    private val heartRateServiceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val heartRateMeasurementCharUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val clientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val heartRateServiceParcelUuid = ParcelUuid(heartRateServiceUuid)
    private val batteryServiceUuid = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val batteryLevelCharUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BleHeartRateManager", "BLE scan failed: $errorCode")
            _connectionState.value = "Error al escanear ($errorCode)"
        }
    }

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBlePermissions()) {
            _connectionState.value = "Permisos Bluetooth pendientes"
            return
        }

        if (!isBluetoothEnabled) {
            _connectionState.value = "Bluetooth apagado"
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = "BLE no disponible"
            return
        }

        disconnect()
        _discoveredDevices.value = emptyList()
        _connectionState.value = "Buscando sensores..."

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (exception: SecurityException) {
            Log.w("BleHeartRateManager", "Missing permission while starting BLE scan", exception)
            _connectionState.value = "Permiso Bluetooth requerido"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasBlePermissions() || !isBluetoothEnabled) return

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            if (_connectionState.value == "Buscando sensores...") {
                _connectionState.value = "Selecciona un sensor"
            }
        } catch (exception: SecurityException) {
            Log.w("BleHeartRateManager", "Missing permission while stopping BLE scan", exception)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (!hasBlePermissions()) {
            _connectionState.value = "Permisos Bluetooth pendientes"
            return
        }

        if (!isBluetoothEnabled) {
            _connectionState.value = "Bluetooth apagado"
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            _connectionState.value = "Sensor no encontrado"
            return
        }

        stopScan()
        _connectionState.value = "Conectando..."
        bluetoothGatt?.close()
        _connectedDevice.value = _discoveredDevices.value.firstOrNull { it.address == address }
        _signalRssi.value = _connectedDevice.value?.rssi
        _batteryLevel.value = null
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (hasBlePermissions()) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (exception: SecurityException) {
                Log.w("BleHeartRateManager", "Missing permission while disconnecting GATT", exception)
            }
        }
        bluetoothGatt = null
        _connectedDevice.value = null
        _signalRssi.value = null
        _batteryLevel.value = null
        _connectionState.value = "Desconectado"
        _heartRate.value = 0
    }

    private fun addScanResult(result: ScanResult) {
        val deviceInfo = result.toDeviceInfo() ?: return
        _discoveredDevices.value = (_discoveredDevices.value
            .filterNot { it.address == deviceInfo.address } + deviceInfo)
            .sortedWith(
                compareByDescending<BleDeviceInfo> { it.isHeartRateSensor }
                    .thenByDescending { it.rssi }
            )
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toDeviceInfo(): BleDeviceInfo? {
        val advertisedName = scanRecord?.deviceName
        val deviceName = try {
            advertisedName ?: device.name
        } catch (exception: SecurityException) {
            advertisedName
        }

        val serviceUuids = scanRecord?.serviceUuids.orEmpty()
        val isHeartRateSensor = heartRateServiceParcelUuid in serviceUuids ||
            deviceName?.contains("coospo", ignoreCase = true) == true ||
            deviceName?.contains("heart", ignoreCase = true) == true ||
            deviceName?.contains("hr", ignoreCase = true) == true

        if (deviceName.isNullOrBlank() && !isHeartRateSensor) return null

        return BleDeviceInfo(
            name = deviceName?.takeIf { it.isNotBlank() } ?: "Sensor BLE",
            address = device.address,
            rssi = rssi,
            isHeartRateSensor = isHeartRateSensor,
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = "Error de conexion ($status)"
                gatt.close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = "Conectado"
                    try {
                        gatt.readRemoteRssi()
                        gatt.discoverServices()
                    } catch (exception: SecurityException) {
                        Log.w("BleHeartRateManager", "Missing permission while discovering services", exception)
                        _connectionState.value = "Permiso Bluetooth requerido"
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = "Desconectado"
                    _heartRate.value = 0
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = "Servicios no disponibles"
                return
            }

            val service = gatt.getService(heartRateServiceUuid)
            val characteristic = service?.getCharacteristic(heartRateMeasurementCharUuid)
            if (characteristic == null) {
                _connectionState.value = "No es sensor cardiaco"
                return
            }

            enableNotifications(gatt, characteristic)
            readBatteryLevel(gatt)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == heartRateMeasurementCharUuid) {
                _heartRate.value = parseHeartRate(characteristic.value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == heartRateMeasurementCharUuid) {
                _heartRate.value = parseHeartRate(value)
                try {
                    gatt.readRemoteRssi()
                } catch (exception: SecurityException) {
                    Log.w("BleHeartRateManager", "Missing permission while reading RSSI", exception)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _signalRssi.value = rssi
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == batteryLevelCharUuid && value.isNotEmpty()) {
                _batteryLevel.value = value[0].toInt() and 0xFF
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(gatt: BluetoothGatt) {
        if (!hasBlePermissions()) return

        val batteryCharacteristic = gatt
            .getService(batteryServiceUuid)
            ?.getCharacteristic(batteryLevelCharUuid)
            ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readCharacteristic(batteryCharacteristic)
            } else {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(batteryCharacteristic)
            }
        } catch (exception: SecurityException) {
            Log.w("BleHeartRateManager", "Missing permission while reading battery", exception)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasBlePermissions()) {
            _connectionState.value = "Permiso Bluetooth requerido"
            return
        }

        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(clientCharacteristicConfigUuid)
            if (descriptor == null) {
                _connectionState.value = "Notificaciones no disponibles"
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            _connectionState.value = "Conectado"
        } catch (exception: SecurityException) {
            Log.w("BleHeartRateManager", "Missing permission while enabling notifications", exception)
            _connectionState.value = "Permiso Bluetooth requerido"
        }
    }

    private fun parseHeartRate(value: ByteArray): Int {
        if (value.size < 2) return 0

        val flags = value[0].toInt()
        return if (flags and 0x01 != 0) {
            if (value.size < 3) 0
            else ((value[2].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        } else {
            value[1].toInt() and 0xFF
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

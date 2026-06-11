package de.nulide.findmydevice.ui.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import de.nulide.findmydevice.securepouch.BleTrackerRepository
import de.nulide.findmydevice.securepouch.DiscoveredDevice
import de.nulide.findmydevice.securepouch.DiscoveredDeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

class BleScanViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val SP_SERVICE_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc051"
        private const val SP_CHAR_DEVICE_ID_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc052"
        private const val SCAN_WINDOW_MS = 12_000L
    }

    private val app = application
    private val handler = Handler(Looper.getMainLooper())
    val bleRepo = BleTrackerRepository(application)

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val btScanner by lazy {
        app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    }
    private val activeGatts = mutableMapOf<String, BluetoothGatt>()

    // ---------- Scanning ----------

    fun startScan() {
        if (_isScanning.value) return
        if (!hasScanPermission()) {
            _error.value = "Bluetooth scan permission not granted"
            return
        }
        val scanner = btScanner ?: run {
            _error.value = "Bluetooth is not available. Make sure Bluetooth is enabled."
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SP_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Brief delay to let BT stack settle after any prior scan
        handler.postDelayed({
            scanner.startScan(listOf(filter), settings, scanCallback)
            _isScanning.value = true
            handler.postDelayed(::stopScan, SCAN_WINDOW_MS)
        }, 400)
    }

    fun stopScan() {
        handler.removeCallbacksAndMessages(null)
        if (_isScanning.value && hasScanPermission()) {
            btScanner?.stopScan(scanCallback)
        }
        _isScanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                val addr = result.device.address
                val existing = _devices.value[addr]
                // Don't overwrite once the user has tapped and we're past FOUND state
                if (existing != null && existing.state != DiscoveredDeviceState.FOUND) return@post

                _devices.update { map ->
                    map + (addr to DiscoveredDevice(
                        address = addr,
                        displayName = result.device.name?.takeIf { it.isNotBlank() } ?: "SecurePouch",
                        rssi = result.rssi,
                        state = DiscoveredDeviceState.FOUND,
                        deviceId = existing?.deviceId,
                    ))
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                _isScanning.value = false
                if (errorCode != SCAN_FAILED_ALREADY_STARTED) {
                    _error.value = "Scan failed (code $errorCode)"
                }
            }
        }
    }

    // ---------- GATT: read Device ID on tap ----------

    fun connectAndReadDeviceId(
        address: String,
        onResult: (deviceId: String, alreadyPaired: Boolean) -> Unit,
        onFail: () -> Unit,
    ) {
        val existing = _devices.value[address] ?: return
        if (existing.state != DiscoveredDeviceState.FOUND) return
        if (!hasConnectPermission()) {
            _error.value = "Bluetooth connect permission not granted"
            return
        }

        setDeviceState(address, DiscoveredDeviceState.CONNECTING)

        val device = app.getSystemService(BluetoothManager::class.java)
            ?.adapter?.getRemoteDevice(address) ?: run {
            setDeviceState(address, DiscoveredDeviceState.ERROR)
            onFail()
            return
        }

        val gatt = device.connectGatt(app, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            activeGatts.remove(address)
                            gatt.close()
                            if (_devices.value[address]?.state == DiscoveredDeviceState.CONNECTING) {
                                setDeviceState(address, DiscoveredDeviceState.ERROR)
                                onFail()
                            }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                handler.post {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        gatt.disconnect()
                        setDeviceState(address, DiscoveredDeviceState.ERROR)
                        onFail()
                        return@post
                    }
                    val char = gatt.getService(UUID.fromString(SP_SERVICE_UUID))
                        ?.getCharacteristic(UUID.fromString(SP_CHAR_DEVICE_ID_UUID))
                    if (char == null) {
                        gatt.disconnect()
                        setDeviceState(address, DiscoveredDeviceState.ERROR)
                        onFail()
                        return@post
                    }
                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(char)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handler.post { handleIdRead(gatt, address, value, onResult, onFail) }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                handler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        handleIdRead(gatt, address, characteristic.value ?: byteArrayOf(), onResult, onFail)
                    } else {
                        gatt.disconnect()
                        setDeviceState(address, DiscoveredDeviceState.ERROR)
                        onFail()
                    }
                }
            }
        })
        activeGatts[address] = gatt
    }

    private fun handleIdRead(
        gatt: BluetoothGatt,
        address: String,
        value: ByteArray,
        onResult: (String, Boolean) -> Unit,
        onFail: () -> Unit,
    ) {
        gatt.disconnect()
        val deviceId = String(value, Charsets.UTF_8).trim()
        if (deviceId.isEmpty()) {
            setDeviceState(address, DiscoveredDeviceState.ERROR)
            onFail()
            return
        }
        val alreadyPaired = bleRepo.getPouchUids().contains(deviceId)
        val newState = if (alreadyPaired) DiscoveredDeviceState.ALREADY_PAIRED else DiscoveredDeviceState.READY
        _devices.update { map ->
            map + (address to (map[address]?.copy(deviceId = deviceId, state = newState) ?: return@update map))
        }
        onResult(deviceId, alreadyPaired)
    }

    // ---------- Registration ----------

    fun registerPouch(
        address: String,
        deviceId: String,
        password: String,
        token: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        setDeviceState(address, DiscoveredDeviceState.PAIRING)
        Thread {
            bleRepo.registerPouch(
                bleUid = deviceId,
                password = password,
                registrationToken = token,
                onSuccess = {
                    handler.post {
                        setDeviceState(address, DiscoveredDeviceState.PAIRED)
                        onSuccess()
                    }
                },
                onError = { msg ->
                    handler.post {
                        setDeviceState(address, DiscoveredDeviceState.ERROR)
                        onError(msg)
                    }
                },
            )
        }.start()
    }

    fun resetDeviceState(address: String) = setDeviceState(address, DiscoveredDeviceState.FOUND)

    private fun setDeviceState(address: String, state: DiscoveredDeviceState) {
        _devices.update { map ->
            val existing = map[address] ?: return@update map
            map + (address to existing.copy(state = state))
        }
    }

    // ---------- Permissions ----------

    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    // ---------- Cleanup ----------

    override fun onCleared() {
        handler.removeCallbacksAndMessages(null)
        if (hasScanPermission()) btScanner?.stopScan(scanCallback)
        activeGatts.values.forEach { it.close() }
        activeGatts.clear()
        super.onCleared()
    }
}

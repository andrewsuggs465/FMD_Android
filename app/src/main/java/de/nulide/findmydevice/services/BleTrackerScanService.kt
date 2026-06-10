package de.nulide.findmydevice.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.securepouch.BleTrackerRepository
import de.nulide.findmydevice.utils.log
import java.util.UUID

/**
 * Foreground service that periodically scans for SecurePouch BLE advertisements.
 *
 * Flow: scan (5 s) → for each found device: connect GATT → read DEVICE_ID char →
 * disconnect → POST phone GPS to FMD server as that pouch device.
 *
 * The scan repeats every [SCAN_INTERVAL_MS]. The phone's last cached GPS fix
 * (from [SettingsRepository.getLastKnownLocation]) is used as the location, so no
 * additional location permission on the foreground service type is required.
 */
class BleTrackerScanService : Service() {

    companion object {
        private const val TAG = "BleTrackerScanService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sp_ble_tracker"

        // 5 s active scan, then 25 s idle = 30 s between location updates
        private const val SCAN_WINDOW_MS = 5_000L
        private const val SCAN_INTERVAL_MS = 30_000L

        // SecurePouch BLE UUIDs (must match firmware/shared/ble_protocol.h)
        private const val SP_SERVICE_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc051"
        private const val SP_CHAR_DEVICE_ID_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc052"

        fun start(context: Context) {
            val intent = Intent(context, BleTrackerScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleTrackerScanService::class.java))
        }
    }

    private lateinit var bleRepo: BleTrackerRepository
    private lateinit var settingsRepo: SettingsRepository

    private val handler = Handler(Looper.getMainLooper())
    private var btScanner = lazy {
        (getSystemService(BluetoothManager::class.java))?.adapter?.bluetoothLeScanner
    }

    // MAC addresses connected this scan window — prevents double-connect on same device
    private val activeGatts = mutableMapOf<String, BluetoothGatt>()

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        bleRepo = BleTrackerRepository(this)
        settingsRepo = SettingsRepository.getInstance(this)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!bleRepo.hasPouches()) {
            this.log().i(TAG, "No pouches registered — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        this.log().i(TAG, "Service started")
        scheduleScan(0)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (hasScanPermission()) {
            btScanner.value?.stopScan(scanCallback)
        }
        activeGatts.values.forEach { it.close() }
        activeGatts.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Scan loop ----------

    private fun scheduleScan(delayMs: Long = SCAN_INTERVAL_MS) {
        handler.postDelayed(::startScan, delayMs)
    }

    private fun startScan() {
        if (!hasScanPermission()) {
            this.log().e(TAG, "Missing BLUETOOTH_SCAN permission — cannot scan")
            scheduleScan()
            return
        }
        val scanner = btScanner.value ?: run {
            this.log().e(TAG, "Bluetooth LE scanner unavailable")
            scheduleScan()
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SP_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        this.log().d(TAG, "Scan started")

        handler.postDelayed(::stopScan, SCAN_WINDOW_MS)
    }

    private fun stopScan() {
        if (hasScanPermission()) {
            btScanner.value?.stopScan(scanCallback)
        }
        this.log().d(TAG, "Scan stopped")
        scheduleScan()
    }

    // ---------- Scan callback ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // ScanCallback fires on a binder thread — post back to main looper
            handler.post { handleScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            this@BleTrackerScanService.log().e(TAG, "BLE scan failed: errorCode=$errorCode")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        if (address in activeGatts) return // already connecting/connected this window

        if (!hasConnectPermission()) {
            this.log().e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        this.log().d(TAG, "SecurePouch device found: $address — connecting GATT")
        val gatt = result.device.connectGatt(this, false, gattCallback(address))
        activeGatts[address] = gatt
    }

    // ---------- GATT callback ----------

    private fun gattCallback(deviceAddress: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        this@BleTrackerScanService.log().d(TAG, "GATT connected: $deviceAddress")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        this@BleTrackerScanService.log().d(TAG, "GATT disconnected: $deviceAddress")
                        activeGatts.remove(deviceAddress)
                        gatt.close()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    this@BleTrackerScanService.log().w(TAG, "Service discovery failed: $deviceAddress")
                    gatt.disconnect()
                    return@post
                }
                val service = gatt.getService(UUID.fromString(SP_SERVICE_UUID))
                val char = service?.getCharacteristic(UUID.fromString(SP_CHAR_DEVICE_ID_UUID))
                if (char == null) {
                    this@BleTrackerScanService.log().w(TAG, "DEVICE_ID char not found on $deviceAddress")
                    gatt.disconnect()
                    return@post
                }
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(char)
            }
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post { onCharRead(gatt, characteristic.uuid, value) }
        }

        // API < 33 (deprecated in API 33 but still called on older devices)
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handler.post { onCharRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf()) }
            } else {
                handler.post { gatt.disconnect() }
            }
        }

        private fun onCharRead(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
            gatt.disconnect()
            if (uuid == UUID.fromString(SP_CHAR_DEVICE_ID_UUID)) {
                val bleUid = String(value, Charsets.UTF_8).trim()
                this@BleTrackerScanService.log().i(TAG, "Pouch detected: bleUid='$bleUid'")
                postLocationForPouch(bleUid)
            }
        }
    }

    // ---------- Location posting ----------

    private fun postLocationForPouch(bleUid: String) {
        if (!bleRepo.getPouchUids().contains(bleUid)) {
            this.log().w(TAG, "Unknown pouch '$bleUid' — not registered, skipping")
            return
        }
        val location = settingsRepo.getLastKnownLocation()
        if (location == null) {
            this.log().w(TAG, "No cached location yet for '$bleUid' — skipping post")
            return
        }
        bleRepo.postLocation(bleUid, location)
    }

    // ---------- Permission helpers ----------

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    // ---------- Notification ----------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SecurePouch Scanner",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active while scanning for nearby SecurePouch devices" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SecurePouch")
            .setContentText("Scanning for nearby pouches…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

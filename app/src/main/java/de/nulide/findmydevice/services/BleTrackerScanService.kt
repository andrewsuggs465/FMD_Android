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
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.FmdLocation
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.securepouch.BleScanScheduler
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
        private const val ALERT_CHANNEL_ID = "sp_ble_alert"

        // Fire a "left behind" alert when a pouch hasn't been seen for this long.
        private const val LEFT_BEHIND_THRESHOLD_MS = 10 * 60 * 1000L  // 10 minutes

        // SecurePouch BLE UUIDs (must match firmware/shared/ble_protocol.h)
        private const val SP_SERVICE_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc051"
        private const val SP_CHAR_DEVICE_ID_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc052"
        private const val SP_CHAR_STATUS_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc053"
        private const val SP_CHAR_CONTROL_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc054"

        // Status byte flags (must match firmware/shared/ble_protocol.h SP_STATUS_*)
        private const val SP_STATUS_ARMED: Byte = 0x02

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

    private lateinit var scanScheduler: BleScanScheduler

    // MAC addresses connected this scan window — prevents double-connect on same device
    private val activeGatts = mutableMapOf<String, BluetoothGatt>()
    // Control opcodes still to be written to a connected pouch (keyed by MAC).
    private val pendingOpcodes = mutableMapOf<String, ArrayDeque<Byte>>()
    // Last RSSI seen per MAC address this scan window; forwarded to postLocation.
    private val lastRssi = mutableMapOf<String, Int>()
    // GATTs held open because the pouch is armed — key = MAC, value = GATT.
    // These are kept alive indefinitely so a real walk-away disconnect fires
    // in seconds rather than at the next scan window (up to 30 s later).
    private val armedGatts = mutableMapOf<String, BluetoothGatt>()
    // MAC → bleUid for devices whose DEVICE_ID has been read this session.
    private val gattToUid = mutableMapOf<String, String>()

    // ---------- Lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        bleRepo = BleTrackerRepository(this)
        settingsRepo = SettingsRepository.getInstance(this)
        scanScheduler = BleScanScheduler(
            postDelayed = { action, delay -> handler.postDelayed(action, delay) },
            doStartScan = ::doStartScan,
            doStopScan  = ::doStopScan,
            onWindowEnd = ::checkLeftBehind,
        )
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!bleRepo.hasPouches()) {
            this.log().i(TAG, "No pouches registered — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        this.log().i(TAG, "Service started")
        scanScheduler.schedule(0)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (hasScanPermission()) {
            btScanner.value?.stopScan(scanCallback)
        }
        activeGatts.values.forEach { it.close() }
        activeGatts.clear()
        armedGatts.values.forEach { it.close() }
        armedGatts.clear()
        pendingOpcodes.clear()
        gattToUid.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Scan loop (delegated to BleScanScheduler) ----------

    /** Returns true if the scan was actually started; false on permission/unavailable. */
    private fun doStartScan(): Boolean {
        if (!hasScanPermission()) {
            this.log().e(TAG, "Missing BLUETOOTH_SCAN permission — cannot scan")
            return false
        }
        val scanner = btScanner.value ?: run {
            this.log().e(TAG, "Bluetooth LE scanner unavailable")
            return false
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SP_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        this.log().d(TAG, "Scan started")
        return true
    }

    private fun doStopScan() {
        if (hasScanPermission()) {
            btScanner.value?.stopScan(scanCallback)
        }
        // Only clear transient scan-window GATTs; armed GATTs stay open.
        activeGatts.entries.removeIf { (mac, gatt) ->
            if (mac !in armedGatts) { gatt.close(); true } else false
        }
        this.log().d(TAG, "Scan stopped")
    }

    private fun checkLeftBehind() {
        val now = System.currentTimeMillis()
        for (uid in bleRepo.getPouchUids()) {
            val lastSeen = bleRepo.getLastSeen(uid) ?: continue
            val elapsed = now - lastSeen
            if (elapsed >= LEFT_BEHIND_THRESHOLD_MS) {
                val minutes = (elapsed / 60_000).toInt()
                fireLeftBehindAlert(uid, minutes)
            }
        }
    }

    private fun fireLeftBehindAlert(uid: String, minutesAgo: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                ALERT_CHANNEL_ID,
                "SecurePouch Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Left-behind and tamper alerts for SecurePouch devices" }
            nm.createNotificationChannel(ch)
        }
        val title = getString(R.string.sp_left_behind_title)
        val text = getString(R.string.sp_left_behind_text, uid, minutesAgo)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        // Use a stable ID per uid so repeated firings update the same notification.
        nm.notify(uid.hashCode(), notification)
        this.log().i(TAG, "Left-behind alert fired for '$uid' ($minutesAgo min ago)")
    }

    private fun fireArmedDisconnectAlert(uid: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                ALERT_CHANNEL_ID,
                "SecurePouch Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Left-behind and tamper alerts for SecurePouch devices" }
            nm.createNotificationChannel(ch)
        }
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SecurePouch out of range")
            .setContentText("'$uid' is armed and has moved out of BLE range")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify("armed_disconnect_$uid".hashCode(), notification)
        this.log().w(TAG, "Armed-disconnect alert fired for '$uid'")
    }

    // ---------- Scan callback ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // ScanCallback fires on a binder thread — post back to main looper
            handler.post { handleScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            this@BleTrackerScanService.log().e(TAG, "BLE scan failed: errorCode=$errorCode")
            handler.post { scanScheduler.onScanFailed() }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address
        lastRssi[address] = result.rssi   // always capture latest RSSI even if already connected

        // If we already have a persistent armed GATT open, just post a location
        // sighting — no need to open a second connection.
        if (address in armedGatts) {
            val gatt = armedGatts[address]!!
            val bleUid = gattToUid[address]
            if (bleUid != null) {
                postLocationForPouch(bleUid, result.rssi)
                bleRepo.storeLastRssi(bleUid, result.rssi)
            }
            // Relay any locally-queued commands via the open armed GATT.
            if (bleUid != null) {
                val opcodes = ArrayDeque<Byte>()
                bleRepo.drainLocalCommands(bleUid).forEach { cmd ->
                    BleTrackerRepository.commandToOpcode(cmd)?.let { opcodes.add(it) }
                }
                if (opcodes.isNotEmpty()) {
                    pendingOpcodes[address] = opcodes
                    writeNextOpcode(gatt)
                }
            }
            return
        }

        if (address in activeGatts) return // already connecting/connected this window

        if (!hasConnectPermission()) {
            this.log().e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }

        this.log().d(TAG, "SecurePouch device found: $address rssi=${result.rssi} dBm — connecting GATT")
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
                        val wasArmed = deviceAddress in armedGatts
                        this@BleTrackerScanService.log().d(TAG,
                            "GATT disconnected: $deviceAddress (wasArmed=$wasArmed)")
                        activeGatts.remove(deviceAddress)
                        armedGatts.remove(deviceAddress)
                        pendingOpcodes.remove(deviceAddress)
                        gatt.close()

                        // If the pouch disconnected while armed, fire a local alert.
                        if (wasArmed) {
                            val bleUid = gattToUid[deviceAddress]
                            if (bleUid != null) {
                                this@BleTrackerScanService.log().w(TAG,
                                    "Armed pouch '$bleUid' disconnected — out of range?")
                                fireArmedDisconnectAlert(bleUid)
                            }
                        }
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
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
            val deviceIdUuid = UUID.fromString(SP_CHAR_DEVICE_ID_UUID)
            val statusUuid   = UUID.fromString(SP_CHAR_STATUS_UUID)

            when (uuid) {
                deviceIdUuid -> {
                    val bleUid = String(value, Charsets.UTF_8).trim()
                    val rssi = lastRssi[gatt.device.address]
                    this@BleTrackerScanService.log().i(TAG, "Pouch detected: bleUid='$bleUid' rssi=${rssi ?: "?"}dBm")
                    gattToUid[gatt.device.address] = bleUid
                    postLocationForPouch(bleUid, rssi)
                    rssi?.let { bleRepo.storeLastRssi(bleUid, it) }

                    if (!bleRepo.getPouchUids().contains(bleUid)) {
                        gatt.disconnect()
                        return
                    }

                    // Read STATUS char next to determine arm state.
                    val service = gatt.getService(UUID.fromString(SP_SERVICE_UUID))
                    val statusChar = service?.getCharacteristic(statusUuid)
                    if (statusChar != null) {
                        @Suppress("DEPRECATION")
                        gatt.readCharacteristic(statusChar)
                    } else {
                        // No STATUS char — fall back to command relay + disconnect.
                        relayCommandsOrDisconnect(gatt, bleUid)
                    }
                }

                statusUuid -> {
                    val statusByte = value.firstOrNull() ?: 0
                    val isArmed = (statusByte.toInt() and SP_STATUS_ARMED.toInt()) != 0
                    val bleUid = gattToUid[gatt.device.address] ?: run {
                        gatt.disconnect(); return
                    }
                    this@BleTrackerScanService.log().d(TAG,
                        "STATUS for '$bleUid': 0x%02x (armed=$isArmed)".format(statusByte))

                    if (isArmed) {
                        // Keep GATT open and subscribe to STATUS notifications so
                        // a walk-away disconnect is detected immediately.
                        armedGatts[gatt.device.address] = gatt
                        enableStatusNotifications(gatt)
                        this@BleTrackerScanService.log().i(TAG,
                            "Pouch '$bleUid' is armed — holding GATT open")
                    }
                    // Whether armed or not, relay any queued commands.
                    relayCommandsOrDisconnect(gatt, bleUid)
                }

                else -> gatt.disconnect()
            }
        }

        /** Subscribe to STATUS notifications on the open GATT. */
        @Suppress("DEPRECATION")
        private fun enableStatusNotifications(gatt: BluetoothGatt) {
            val service = gatt.getService(UUID.fromString(SP_SERVICE_UUID)) ?: return
            val statusChar = service.getCharacteristic(UUID.fromString(SP_CHAR_STATUS_UUID)) ?: return
            gatt.setCharacteristicNotification(statusChar, true)
            // Write the CCCD (0x0001 = notify) to enable server-side notifications.
            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val cccd = statusChar.getDescriptor(cccdUuid) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT.let {
                    byteArrayOf(0x01, 0x00)
                })
            } else {
                @Suppress("DEPRECATION")
                cccd.value = byteArrayOf(0x01, 0x00)
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post { handleStatusNotification(gatt, value) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handler.post { handleStatusNotification(gatt, characteristic.value ?: byteArrayOf()) }
        }

        private fun handleStatusNotification(gatt: BluetoothGatt, value: ByteArray) {
            val statusByte = value.firstOrNull() ?: return
            val isArmed = (statusByte.toInt() and SP_STATUS_ARMED.toInt()) != 0
            val bleUid = gattToUid[gatt.device.address] ?: return
            this@BleTrackerScanService.log().d(TAG,
                "STATUS notify for '$bleUid': 0x%02x (armed=$isArmed)".format(statusByte))
            if (!isArmed && gatt.device.address in armedGatts) {
                // Pouch was just disarmed — release the held-open GATT.
                armedGatts.remove(gatt.device.address)
                this@BleTrackerScanService.log().i(TAG,
                    "Pouch '$bleUid' disarmed — releasing persistent GATT")
                // Disconnect if no commands are pending.
                if (pendingOpcodes[gatt.device.address]?.isEmpty() != false) {
                    gatt.disconnect()
                }
            }
        }

        /** Queue + relay commands over this GATT; disconnect when done (unless armed). */
        private fun relayCommandsOrDisconnect(gatt: BluetoothGatt, bleUid: String) {
            val opcodes = ArrayDeque<Byte>()
            bleRepo.drainLocalCommands(bleUid).forEach { cmd ->
                BleTrackerRepository.commandToOpcode(cmd)?.let { opcodes.add(it) }
            }
            pendingOpcodes[gatt.device.address] = opcodes

            bleRepo.pollServerCommand(bleUid) { cmd ->
                BleTrackerRepository.commandToOpcode(cmd)?.let { op ->
                    handler.post {
                        val q = pendingOpcodes[gatt.device.address]
                        if (q != null) {
                            q.add(op)
                            if (q.size == 1) writeNextOpcode(gatt)
                        }
                    }
                }
            }

            if (opcodes.isNotEmpty()) {
                writeNextOpcode(gatt)
            } else {
                handler.postDelayed({
                    // Only disconnect if not armed and no commands arrived from server poll.
                    if (gatt.device.address !in armedGatts &&
                        pendingOpcodes[gatt.device.address]?.isEmpty() != false) {
                        gatt.disconnect()
                    }
                }, 2_000)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post {
                val q = pendingOpcodes[gatt.device.address]
                if (q != null && q.isNotEmpty()) {
                    q.removeFirst()
                }
                if (q != null && q.isNotEmpty()) {
                    writeNextOpcode(gatt)
                } else if (gatt.device.address !in armedGatts) {
                    // All commands written and not armed — disconnect.
                    gatt.disconnect()
                }
                // If armed, leave the connection open; STATUS notify handles teardown.
            }
        }
    }

    /** Write the front opcode of the device's pending queue to the CONTROL char. */
    @Suppress("DEPRECATION")
    private fun writeNextOpcode(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) {
            gatt.disconnect()
            return
        }
        val opcode = pendingOpcodes[gatt.device.address]?.firstOrNull() ?: run {
            gatt.disconnect()
            return
        }
        val service = gatt.getService(UUID.fromString(SP_SERVICE_UUID))
        val control = service?.getCharacteristic(UUID.fromString(SP_CHAR_CONTROL_UUID))
        if (control == null) {
            this.log().w(TAG, "CONTROL char not found — cannot relay command")
            gatt.disconnect()
            return
        }
        this.log().i(TAG, "Writing control opcode 0x%02x to pouch".format(opcode))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                control,
                byteArrayOf(opcode),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            control.value = byteArrayOf(opcode)
            control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(control)
        }
    }

    // ---------- Location posting ----------

    private fun postLocationForPouch(bleUid: String, rssi: Int? = null) {
        if (!bleRepo.getPouchUids().contains(bleUid)) {
            this.log().w(TAG, "Unknown pouch '$bleUid' — not registered, skipping")
            return
        }
        val location = getPhoneLocation()
        if (location == null) {
            this.log().w(TAG, "No location available for '$bleUid' — skipping post")
            return
        }
        bleRepo.postLocation(bleUid, location, rssi)
    }

    @Suppress("MissingPermission")
    private fun getPhoneLocation(): FmdLocation? {
        val lm = getSystemService(LocationManager::class.java)
        // Try GPS first, fall back to network, then to FMD's own settings cache
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = lm?.getLastKnownLocation(provider)
            if (loc != null) {
                return FmdLocation.fromAndroidLocation(this, loc)
            }
        }
        return settingsRepo.getLastKnownLocation()
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

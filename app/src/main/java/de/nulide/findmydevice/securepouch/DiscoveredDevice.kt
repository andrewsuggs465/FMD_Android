package de.nulide.findmydevice.securepouch

data class DiscoveredDevice(
    val address: String,
    val displayName: String,
    val rssi: Int,
    val state: DiscoveredDeviceState = DiscoveredDeviceState.FOUND,
    val deviceId: String? = null,
)

enum class DiscoveredDeviceState {
    FOUND,           // advertisement seen, not yet tapped
    CONNECTING,      // GATT connecting to read Device ID
    READY,           // Device ID read, ready to register
    ALREADY_PAIRED,  // already registered in BleTrackerRepository
    PAIRING,         // registerPouch() in progress
    PAIRED,          // registered successfully
    ERROR,           // GATT failed
}

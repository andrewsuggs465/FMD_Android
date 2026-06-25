package de.nulide.findmydevice.securepouch

/**
 * Manages the scan-window / idle-interval loop for [BleTrackerScanService].
 *
 * Separated from the service so the state-machine logic can be unit-tested
 * without Android framework dependencies.
 *
 * Lifecycle:
 *   post(0) → startScan() → [SCAN_WINDOW_MS] → stopScan() → [SCAN_INTERVAL_MS] → startScan() …
 *
 * On hardware failure (onScanFailed):
 *   isScanning is cleared and a backoff reschedule is posted. Android throttles
 *   apps that start/stop scans more than 5 times in 30 s; the backoff ensures we
 *   never exceed that rate even if failures cascade.
 */
class BleScanScheduler(
    /** Post a delayed action; wraps Handler.postDelayed. */
    private val postDelayed: (action: () -> Unit, delayMs: Long) -> Unit,
    /** Start the actual BLE scan; returns true if the scanner was available. */
    private val doStartScan: () -> Boolean,
    /** Stop the actual BLE scan. */
    private val doStopScan: () -> Unit,
    /** Called after each scan window ends (left-behind checks, etc.). */
    private val onWindowEnd: () -> Unit,
) {
    companion object {
        const val SCAN_WINDOW_MS   = 5_000L
        const val SCAN_INTERVAL_MS = 30_000L

        // Retry delay after a hardware failure or missing-scanner condition.
        // Must be long enough that even 5 consecutive failures land in separate
        // 30-second Android throttle windows (each window allows 5 start/stops).
        const val ERROR_BACKOFF_MS = 35_000L
    }

    var isScanning: Boolean = false
        private set

    /** Kick off the first scan immediately (delayMs=0) or after an interval. */
    fun schedule(delayMs: Long = SCAN_INTERVAL_MS) {
        postDelayed(::startScan, delayMs)
    }

    fun startScan() {
        if (isScanning) return
        val started = doStartScan()
        if (!started) {
            // Scanner unavailable — back off before retrying so we don't hammer.
            schedule(ERROR_BACKOFF_MS)
            return
        }
        isScanning = true
        postDelayed(::stopScan, SCAN_WINDOW_MS)
    }

    fun stopScan() {
        doStopScan()
        isScanning = false
        onWindowEnd()
        schedule(SCAN_INTERVAL_MS)
    }

    /** Called by the ScanCallback when the hardware rejects a scan. */
    fun onScanFailed() {
        isScanning = false
        schedule(ERROR_BACKOFF_MS)
    }
}

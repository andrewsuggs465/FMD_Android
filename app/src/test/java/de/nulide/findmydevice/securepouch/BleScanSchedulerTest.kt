package de.nulide.findmydevice.securepouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BleScanSchedulerTest {

    // Captured (action, delayMs) pairs in order of posting.
    private val posted = mutableListOf<Pair<() -> Unit, Long>>()
    private var scannerAvailable = true
    private var stopCalls = 0
    private var windowEndCalls = 0

    private lateinit var scheduler: BleScanScheduler

    @Before
    fun setUp() {
        posted.clear()
        scannerAvailable = true
        stopCalls = 0
        windowEndCalls = 0
        scheduler = BleScanScheduler(
            postDelayed  = { action, delay -> posted.add(action to delay) },
            doStartScan  = { scannerAvailable },
            doStopScan   = { stopCalls++ },
            onWindowEnd  = { windowEndCalls++ },
        )
    }

    /** Pop and invoke the next pending action. Returns its delay. */
    private fun runNext(): Long {
        val (action, delay) = posted.removeFirst()
        action()
        return delay
    }

    /** Assert the next pending action has the given delay without running it. */
    private fun assertNextDelay(expected: Long) {
        assertEquals(expected, posted.first().second)
    }

    // --- Happy path ---

    @Test
    fun `schedule posts startScan with given delay`() {
        scheduler.schedule(0)
        assertEquals(1, posted.size)
        assertEquals(0L, posted[0].second)
    }

    @Test
    fun `startScan sets isScanning and posts stopScan after window`() {
        scheduler.startScan()

        assertTrue(scheduler.isScanning)
        assertEquals(1, posted.size)
        assertEquals(BleScanScheduler.SCAN_WINDOW_MS, posted[0].second)
    }

    @Test
    fun `stopScan clears isScanning, fires onWindowEnd, reschedules at interval`() {
        scheduler.startScan()   // posts stopScan
        posted.removeFirst().first()  // invoke stopScan

        assertFalse(scheduler.isScanning)
        assertEquals(1, stopCalls)
        assertEquals(1, windowEndCalls)
        assertEquals(1, posted.size)
        assertEquals(BleScanScheduler.SCAN_INTERVAL_MS, posted[0].second)
    }

    @Test
    fun `full cycle repeats correctly`() {
        scheduler.schedule(0)

        // Cycle 1: schedule posts startScan at 0
        assertEquals(0L, runNext())           // startScan runs, posts stopScan at SCAN_WINDOW_MS
        assertTrue(scheduler.isScanning)
        assertNextDelay(BleScanScheduler.SCAN_WINDOW_MS)

        assertEquals(BleScanScheduler.SCAN_WINDOW_MS, runNext())  // stopScan, posts startScan at SCAN_INTERVAL_MS
        assertFalse(scheduler.isScanning)
        assertEquals(1, windowEndCalls)
        assertNextDelay(BleScanScheduler.SCAN_INTERVAL_MS)

        // Cycle 2
        assertEquals(BleScanScheduler.SCAN_INTERVAL_MS, runNext()) // startScan, posts stopScan
        assertTrue(scheduler.isScanning)

        assertEquals(BleScanScheduler.SCAN_WINDOW_MS, runNext())   // stopScan, reschedule
        assertFalse(scheduler.isScanning)
        assertEquals(2, windowEndCalls)
    }

    // --- Error paths ---

    @Test
    fun `startScan when scanner unavailable backs off at ERROR_BACKOFF_MS`() {
        scannerAvailable = false
        scheduler.startScan()

        assertFalse(scheduler.isScanning)
        assertEquals(0, stopCalls)
        assertEquals(1, posted.size)
        assertEquals(BleScanScheduler.ERROR_BACKOFF_MS, posted[0].second)
    }

    @Test
    fun `onScanFailed clears isScanning and backs off`() {
        scheduler.startScan()   // sets isScanning = true
        assertTrue(scheduler.isScanning)
        posted.clear()          // discard the stopScan post

        scheduler.onScanFailed()

        assertFalse(scheduler.isScanning)
        assertEquals(1, posted.size)
        assertEquals(BleScanScheduler.ERROR_BACKOFF_MS, posted[0].second)
    }

    @Test
    fun `repeated hardware failures never schedule faster than ERROR_BACKOFF_MS`() {
        scannerAvailable = false
        scheduler.schedule(0)

        // Each run of startScan should post a backoff, never 0.
        repeat(5) {
            val delay = runNext()
            // First run is at delay=0 (from schedule(0)); after that every retry
            // must be at ERROR_BACKOFF_MS.
            if (it > 0) assertEquals(BleScanScheduler.ERROR_BACKOFF_MS, delay)
            assertNextDelay(BleScanScheduler.ERROR_BACKOFF_MS)
        }
    }

    @Test
    fun `isScanning guard prevents double-start`() {
        scheduler.startScan()
        assertTrue(scheduler.isScanning)
        val postsAfterFirst = posted.size

        scheduler.startScan()  // should be a no-op

        assertEquals(postsAfterFirst, posted.size)
        assertEquals(1, posted.count { it.second == BleScanScheduler.SCAN_WINDOW_MS })
    }

    @Test
    fun `recovery after failure resumes normal cycle`() {
        scannerAvailable = false
        scheduler.schedule(0)

        runNext()  // startScan fails, posts backoff
        assertFalse(scheduler.isScanning)
        assertNextDelay(BleScanScheduler.ERROR_BACKOFF_MS)

        // Scanner comes back before the backoff fires
        scannerAvailable = true
        runNext()  // startScan succeeds, posts stopScan
        assertTrue(scheduler.isScanning)
        assertNextDelay(BleScanScheduler.SCAN_WINDOW_MS)

        runNext()  // stopScan, reschedule at SCAN_INTERVAL_MS
        assertFalse(scheduler.isScanning)
        assertEquals(1, windowEndCalls)
        assertNextDelay(BleScanScheduler.SCAN_INTERVAL_MS)
    }
}

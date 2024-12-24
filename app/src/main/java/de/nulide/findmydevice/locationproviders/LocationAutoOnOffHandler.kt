package de.nulide.findmydevice.locationproviders

import android.content.Context
import android.location.LocationManager
import android.os.Build
import de.nulide.findmydevice.permissions.WriteSecureSettingsPermission
import de.nulide.findmydevice.utils.SecureSettings
import de.nulide.findmydevice.utils.SingletonHolder


class LocationAutoOnOffHandler private constructor(val context: Context) {

    companion object :
        SingletonHolder<LocationAutoOnOffHandler, Context>(::LocationAutoOnOffHandler) {}

    private var isTurnedOnByUs = false

    /**
     * Set of jobs that are currently running that need the location to be on.
     */
    private val runningJobs = mutableSetOf<Int>()

    // Synchronise adding and removing jobs. This avoids race conditions between concurrent jobs
    // which could result in broken state.
    private val lock = Any()

    /**
     * Turns the location on for the job with the given id.
     *
     * @return True if the location is on, false otherwise.
     */
    fun addJob(jobId: Int): Boolean = synchronized(lock) {
        if (isLocationOn(context)) {
            return true
        }
        if (!WriteSecureSettingsPermission().isGranted(context)) {
            return false
        }

        SecureSettings.turnGPS(context, true)
        isTurnedOnByUs = true
        runningJobs.add(jobId)

        // Give it some time to turn on
        Thread.sleep(500)

        return true
    }

    fun removeJob(jobId: Int) = synchronized(lock) {
        runningJobs.remove(jobId)

        if (runningJobs.isEmpty() && isTurnedOnByUs) {
            SecureSettings.turnGPS(context, false)
            isTurnedOnByUs = false
        }
    }
}


fun isLocationOn(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
    } else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

package de.nulide.findmydevice.locationproviders

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.FmdLocation
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.permissions.LocationPermission
import de.nulide.findmydevice.transports.Transport
import de.nulide.findmydevice.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue


// Testing on GrapheneOS has shown that if you are in a reasonable place for GPS
// (open sky, in a car/bus/train, in a building near a window, ...),
// you usually get a GPS position in < 1 minute. Often in < 30 seconds.
//
// Therefore, set the timeout to 2 minutes.
// It's better to fail fast, inform the user, and let them decide what to do
// (Resend "locate gps"? Send "locate cell" instead?).
// A shorter timeout also reduces the battery impact for commands that won't succeed anyway.
// And it reduces the risk of Android punishing FMD for excessive foreground service usage.
const val MAX_GPS_DURATION_MILLIS = 2 * 60 * 1000L

private const val UPDATE_INTERVAL_MILLIS = 2 * 1000L

// LocationManager.FUSED_PROVIDER was only added in SDK 31
const val FUSED_PROVIDER = "fused"

/**
 * Only call this provider via the LocateCommand!
 * (because it handles things like LocationAutoOnOff centrally)
 */
class GpsLocationProvider<T>(
    private val context: Context,
    private val transport: Transport<T>,
    private var requestedProvider: String,
) : LocationProvider(), LocationListener {

    companion object {
        private val TAG = GpsLocationProvider::class.simpleName
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var deferred: CompletableDeferred<Unit>? = null

    private var currBestLocation: FmdLocation? = null
    private var locationCount = 0
    private var previousAccuracy = 0F

    private var coroutineScope: CoroutineScope? = null

    @SuppressLint("MissingPermission") // linter is not good enough to recognize the check
    override suspend fun getAndSendLocation(): Deferred<Unit> {
        val def = CompletableDeferred<Unit>()
        deferred = def

        if (!LocationPermission().isGranted(context)) {
            context.log().i(TAG, "Missing location permission, cannot get location")
            def.complete(Unit)
            return def
        }

        // Countdown to stop the job after some timeout
        coroutineScope = CoroutineScope(currentCoroutineContext())
        coroutineScope?.launch(Dispatchers.IO) {
            delay(MAX_GPS_DURATION_MILLIS - 5_000)

            context.log().d(TAG, "Stopping locating due to timeout. Sending best location so far.")
            sendBestLocationAndFinish()
        }

        if (!locationManager.isProviderEnabled(requestedProvider)) {
            val msg = context.getString(R.string.cmd_locate_provider_disabled, requestedProvider)
            context.log().d(TAG, msg)
            transport.send(context, msg)
            def.complete(Unit)
            return def
        }

        locationManager.requestLocationUpdates(
            requestedProvider,
            UPDATE_INTERVAL_MILLIS,
            0f,
            this@GpsLocationProvider,
            Looper.getMainLooper(),
        )

        transport.send(context, context.getString(R.string.cmd_locate_response_gps_will_follow))
        return def
    }

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(asFallBackForCurrentLocation: Boolean = false) {
        val lastLocationFromAndroid =
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        val settings = SettingsRepository.getInstance(context)
        val cachedLoc = settings.getLastKnownLocation()

        if (lastLocationFromAndroid == null) {
            if (asFallBackForCurrentLocation) {
                // If current location was requested originally, don't fall back to cached location.
                transport.send(context, context.getString(R.string.cmd_locate_response_gps_fail))
            } else if (cachedLoc != null) {
                // If last location was requested, fall back to cached location.
                transport.sendNewLocation(context, cachedLoc)
            } else {
                // No location and nothing to fall back to.
                transport.send(
                    context,
                    context.getString(R.string.cmd_locate_last_known_location_not_available)
                )
            }
        } else {
            if (cachedLoc == null) {
                // Update our cached location
                onLocationChanged(lastLocationFromAndroid)
            } else {
                // If the last location from the LocationManager is newer than our cached location,
                // update our cached location.
                if (lastLocationFromAndroid.time > cachedLoc.timeMillis) {
                    onLocationChanged(lastLocationFromAndroid)
                } else {
                    transport.sendNewLocation(context, cachedLoc)
                }
            }
        }
        cleanup()
    }

    override fun onLocationChanged(location: Location) {
        val fmdLocation = FmdLocation.fromAndroidLocation(context, location)
        context.log().d(
            TAG,
            "Location found by ${fmdLocation.provider} with accuracy ${fmdLocation.accuracy} m."
        )

        locationCount += 1
        val isAccDiffLarge = isAccuracyDiffLarge(fmdLocation)
        updateCurrentBestLocation(fmdLocation)

        // Skip a few initial locations to wait for a more accurate GPS-based location.
        // Wait either until the accuracy does not improve anymore or for a fixed number of results.
        if (requestedProvider == LocationManager.GPS_PROVIDER
            && isAccDiffLarge
            && locationCount < 15
        ) {
            return
        }
        // Return this location and finish
        val settings = SettingsRepository.getInstance(context)
        settings.storeLastKnownLocation(fmdLocation)
        transport.sendNewLocation(context, fmdLocation)
        cleanup()
    }

    private fun isAccuracyDiffLarge(fmdLocation: FmdLocation): Boolean {
        if (fmdLocation.accuracy == null) {
            return false
        }
        val diff = (previousAccuracy - fmdLocation.accuracy).absoluteValue
        if (diff == 0F) {
            // This is very likely the same location => Keep running.
            return false
        }
        previousAccuracy = fmdLocation.accuracy
        return diff > 5 // meter
    }

    private fun updateCurrentBestLocation(newLocation: FmdLocation) {
        val currBest = currBestLocation
        if (
        // any new location is better than no accuracy
            currBest == null || currBest.accuracy == null
            // if we already have a current best: is the new location better?
            || (newLocation.accuracy != null && newLocation.accuracy < currBest.accuracy)
        ) {
            currBestLocation = newLocation
            val settings = SettingsRepository.getInstance(context)
            settings.storeLastKnownLocation(newLocation)
        }
    }

    private fun sendBestLocationAndFinish() {
        val currBest = currBestLocation
        if (currBest != null) {
            transport.sendNewLocation(context, currBest)
        } else {
            val msg = context.getString(R.string.cmd_locate_response_gps_fail)
            context.log().d(TAG, msg)
            transport.send(context, msg)
        }
        cleanup()
    }

    private fun cleanup() {
        locationManager.removeUpdates(this)
        coroutineScope?.cancel()
        deferred?.complete(Unit)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        // unused
    }

    override fun onProviderEnabled(provider: String) {
        // unused
    }

    override fun onProviderDisabled(provider: String) {
        // unused
    }
}

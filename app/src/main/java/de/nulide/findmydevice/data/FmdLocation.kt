package de.nulide.findmydevice.data

import android.content.Context
import android.location.Location
import de.nulide.findmydevice.utils.Utils
import de.nulide.findmydevice.utils.Utils.Companion.getOpenStreetMapLink
import java.util.Date


data class FmdLocation(
    val lat: Double,
    val lon: Double,

    val provider: String,
    val batteryLevel: Int,

    // Or this? -> Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeInMillis
    val timeMillis: Long = System.currentTimeMillis(),
) {

    companion object {
        fun fromAndroidLocation(context: Context, loc: Location): FmdLocation {
            return FmdLocation(
                lat = loc.latitude,
                lon = loc.longitude,
                provider = loc.provider ?: "GPS",
                batteryLevel = Utils.getBatteryLevel(context),
                timeMillis = loc.time,
            )
        }
    }

    override fun toString(): String {
        val string = StringBuilder()
            .append("$provider:\n")
            .append("Lat: $lat\n")
            .append("Lon: $lon\n")

        string.append("Time: ${Date(timeMillis)}\n")
            .append("Battery: $batteryLevel %\n")
            .append(getOpenStreetMapLink(lat, lon))
        return string.toString()
    }
}

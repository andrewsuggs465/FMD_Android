package de.nulide.findmydevice.commands

import android.content.Context
import android.location.LocationManager.GPS_PROVIDER
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.locationproviders.CellLocationProvider
import de.nulide.findmydevice.locationproviders.FUSED_PROVIDER
import de.nulide.findmydevice.locationproviders.GpsLocationProvider
import de.nulide.findmydevice.locationproviders.LocationAutoOnOffHandler
import de.nulide.findmydevice.permissions.LocationPermission
import de.nulide.findmydevice.permissions.WriteSecureSettingsPermission
import de.nulide.findmydevice.transports.Transport
import de.nulide.findmydevice.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext


class LocateCommand(context: Context) : Command(context) {

    companion object {
        private val TAG = LocateCommand::class.simpleName
    }

    override val keyword = "locate"

    // Do not document the accuracy parameter. *It is for debugging.*
    // Users should rely on the normal auto-convergence of the "gps" option.
    // In any real scenario where you don't know where your device is
    // (At walking speed under clear sky? Moving at 120 km/h in a train?)
    // you don't know a-priori what accuracy is possible.
    override val usage = "locate [last | all | cell | fused | gps]"

    @get:DrawableRes
    override val icon = R.drawable.ic_location

    @get:StringRes
    override val shortDescription = R.string.cmd_locate_description_short

    @get:StringRes
    override val longDescription = R.string.cmd_locate_description_long

    override val requiredPermissions = listOf(LocationPermission())

    override val optionalPermissions = listOf(WriteSecureSettingsPermission())

    override suspend fun <T> executeInternal(
        args: List<String>,
        transport: Transport<T>,
    ) {
        // ignore everything except the first option (if it exists)
        val option = args.getOrElse(0) { "all" }

        // fmd locate last
        if (args.contains("last")) {
            withContext(Dispatchers.IO) {
                val provider = GpsLocationProvider(context, transport, FUSED_PROVIDER, null)
                provider.getLastKnownLocation()
            }
            // Even if last location is not available, return here.
            // Because requesting "last" explicitly asks not to refresh the location.
            return
        }

        var accuracy: Int? = null
        try {
            accuracy = args.firstOrNull { it.startsWith("acc=") }?.substring(4)?.toInt()
        } catch (e: NumberFormatException) {
            context.log().w(TAG, "Invalid accuracy, using null")
        }

        val locOnOffHandler = LocationAutoOnOffHandler.getInstance(context)
        val res = locOnOffHandler.addJob()

        if (!res.isLocationOn) {
            context.log().w(
                TAG,
                "Cannot locate: Location is off and missing permission WRITE_SECURE_SETTINGS"
            )
            transport.send(context, context.getString(R.string.cmd_locate_response_location_off))
            return
        }

        val deferred = CompletableDeferred<Unit>()

        // build the location providers
        val providers = when (option) {
            "cell" -> listOf(CellLocationProvider(context, transport))
            "fused" -> listOf(GpsLocationProvider(context, transport, FUSED_PROVIDER, accuracy))
            "gps" -> listOf(GpsLocationProvider(context, transport, GPS_PROVIDER, accuracy))
            else ->
                listOf(
                    GpsLocationProvider(context, transport, FUSED_PROVIDER, accuracy),
                    CellLocationProvider(context, transport)
                )
        }

        val cleanupHandler = {
            locOnOffHandler.removeJob(res.jobId)
            deferred.complete(Unit)
        }

        // Make sure we clean up and properly finish the job, even when something else
        // cancels the coroutine. E.g., when the system calls onStopJob() the coroutine
        // is cancelled.
        //
        // Proper cleanup is important for the regular background upload, e.g., we need
        // to make sure that the location auto-on/off runs.
        currentCoroutineContext().job.invokeOnCompletion { _ ->
            cleanupHandler()
        }

        // run the providers and get the locations
        withContext(Dispatchers.IO) {
            providers
                // launch all providers in parallel
                .map { prov -> prov.getAndSendLocation() }
                // await all providers
                .forEach { deferredProvider -> deferredProvider.await() }

            // finish the job once all providers have finished
            cleanupHandler()
        }
        deferred.await()
    }
}

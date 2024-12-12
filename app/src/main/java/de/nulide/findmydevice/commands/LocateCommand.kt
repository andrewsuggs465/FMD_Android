package de.nulide.findmydevice.commands

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.locationproviders.CellLocationProvider
import de.nulide.findmydevice.locationproviders.GpsLocationProvider
import de.nulide.findmydevice.permissions.LocationPermission
import de.nulide.findmydevice.permissions.WriteSecureSettingsPermission
import de.nulide.findmydevice.services.FmdJobService
import de.nulide.findmydevice.transports.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class LocateCommand(context: Context) : Command(context) {

    override val keyword = "locate"
    override val usage = "locate [last | all | cell | gps]"

    @get:DrawableRes
    override val icon = R.drawable.ic_location

    @get:StringRes
    override val shortDescription = R.string.cmd_locate_description_short

    @get:StringRes
    override val longDescription = R.string.cmd_locate_description_long

    override val requiredPermissions = listOf(LocationPermission())

    override val optionalPermissions = listOf(WriteSecureSettingsPermission())

    override fun <T> executeInternal(
        args: List<String>,
        transport: Transport<T>,
        coroutineScope: CoroutineScope,
        job: FmdJobService?,
    ) {
        // ignore everything except the first option (if it exists)
        val option = args.getOrElse(0) { "all" }

        // fmd locate last
        if (args.contains("last")) {
            coroutineScope.launch(Dispatchers.IO) {
                GpsLocationProvider(context, transport).getLastKnownLocation()
                job?.jobFinished()
            }
            // Even if last location is not available, return here.
            // Because requesting "last" explicitly asks not to refresh the location.
            return
        }

        // build the location providers
        val providers = when (option) {
            "cell" -> listOf(CellLocationProvider(context, transport))
            "gps" -> listOf(GpsLocationProvider(context, transport))
            else ->
                listOf(
                    GpsLocationProvider(context, transport),
                    CellLocationProvider(context, transport)
                )
        }

        // run the providers and get the locations
        val lambda = suspend {
            providers
                // launch all providers in parallel
                .map { prov -> prov.getAndSendLocation() }
                // await all providers
                .forEach { deferred -> deferred.await() }

            // finish the job once all providers have finished
            job?.jobFinished()
        }
        coroutineScope.launch(Dispatchers.IO) { lambda() }
    }
}

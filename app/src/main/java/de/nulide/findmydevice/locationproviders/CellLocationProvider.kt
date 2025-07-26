package de.nulide.findmydevice.locationproviders

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.net.BeaconDbRepository
import de.nulide.findmydevice.net.OpenCelliDRepository
import de.nulide.findmydevice.net.OpenCelliDSpec
import de.nulide.findmydevice.transports.Transport
import de.nulide.findmydevice.utils.CellParameters
import de.nulide.findmydevice.utils.log
import de.nulide.findmydevice.utils.prettyPrint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.Calendar
import java.util.TimeZone


/**
 * Only call this provider via the LocateCommand!
 * (because it handles things like LocationAutoOnOff centrally)
 */
class CellLocationProvider<T>(
    private val context: Context,
    private val transport: Transport<T>,
) : LocationProvider() {

    companion object {
        private val TAG = CellLocationProvider::class.simpleName
    }

    private var deferred = CompletableDeferred<Unit>()

    @Volatile
    private var ocidFinished = false

    @Volatile
    private var beaconDbFinished = false

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun getAndSendLocation(): Deferred<Unit> {
        deferred = CompletableDeferred<Unit>()
        ocidFinished = false
        beaconDbFinished = false

        val paras = CellParameters.queryCellParametersFromTelephonyManager(context)
        if (paras.isEmpty()) {
            context.log().i(TAG, "Cell paras are null. Are you connected to the cellular network?")
            transport.send(context, context.getString(R.string.OpenCellId_test_no_connection))
            deferred.complete(Unit)
            return deferred
        }

        // Since internally both repositories use Volley with callbacks, the requests don't block on each other.
        // TODO: query all
        queryOpenCelliD(paras.first())
        queryBeaconDb(paras)
        return deferred
    }

    private fun queryOpenCelliD(paras: CellParameters) {
        val settings = SettingsRepository.getInstance(context)
        val apiAccessToken = settings.get(Settings.SET_OPENCELLID_API_KEY) as String
        if (apiAccessToken.isEmpty()) {
            val msg = "Cannot query OpenCelliD: Missing API Token"
            context.log().i(TAG, msg)
            transport.send(context, msg)
            deferred.complete(Unit)
            return
        }

        context.log().d(TAG, "Querying OpenCelliD")
        val ocidRepo = OpenCelliDRepository.getInstance(OpenCelliDSpec(context))
        ocidRepo.getCellLocation(
            paras, apiAccessToken,
            onSuccess = {
                context.log().d(TAG, "Location found by OpenCelliD")
                val timeMillis = Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeInMillis
                storeLastKnownLocation(context, it.lat, it.lon, timeMillis)
                transport.sendNewLocation(context, "OpenCelliD", it.lat, it.lon, timeMillis)
                ocidFinished = true
                onFinished()
            },
            onError = {
                context.log().i(TAG, "Failed to get location from OpenCelliD")
                val msg = context.getString(
                    R.string.cmd_locate_response_opencellid_failed,
                    it.url,
                    paras.prettyPrint()
                )
                transport.send(context, msg)
                ocidFinished = true
                onFinished()
            },
        )
    }

    private fun queryBeaconDb(paras: List<CellParameters>) {
        context.log().d(TAG, "Querying BeaconDB")
        val beaconDbRepo = BeaconDbRepository.getInstance(context)
        beaconDbRepo.getCellLocation(
            paras,
            onSuccess = {
                context.log().d(TAG, "Location found by BeaconDB")
                val timeMillis = Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeInMillis

                storeLastKnownLocation(context, it.lat.toString(), it.lon.toString(), timeMillis)
                transport.sendNewLocation(
                    context,
                    "BeaconDB",
                    it.lat.toString(),
                    it.lon.toString(),
                    timeMillis
                )

                beaconDbFinished = true
                onFinished()
            },
            onError = {
                context.log().i(TAG, "Failed to get location from BeaconDB")
                val msg = context.getString(
                    R.string.cmd_locate_response_beacondb_failed,
                    paras.prettyPrint()
                )
                transport.send(context, msg)
                beaconDbFinished = true
                onFinished()
            },
        )
    }

    private fun onFinished() {
        if (ocidFinished && beaconDbFinished) {
            deferred.complete(Unit)
        }
    }
}

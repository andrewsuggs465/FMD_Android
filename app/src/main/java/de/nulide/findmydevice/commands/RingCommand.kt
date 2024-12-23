package de.nulide.findmydevice.commands

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.permissions.DoNotDisturbAccessPermission
import de.nulide.findmydevice.permissions.OverlayPermission
import de.nulide.findmydevice.permissions.Permission
import de.nulide.findmydevice.services.FmdJobService
import de.nulide.findmydevice.transports.Transport
import de.nulide.findmydevice.utils.RingerUtils
import kotlinx.coroutines.CoroutineScope


class RingCommand(context: Context) : Command(context) {

    override val keyword = "ring"
    override val usage = "ring [long]"

    @get:DrawableRes
    override val icon = R.drawable.ic_volume_up

    @get:StringRes
    override val shortDescription = R.string.cmd_ring_description_short

    override val longDescription = null

    // DNDAccess is required for changing the alarm mode and volume
    // TODO(#145): Implement this without needing the overlay permission
    override val requiredPermissions = listOf(DoNotDisturbAccessPermission(), OverlayPermission())

    override fun <T> executeInternal(
        args: List<String>,
        transport: Transport<T>,
        coroutineScope: CoroutineScope,
        job: FmdJobService?,
    ) {
        val firstArg = args.getOrElse(0) { "" }

        var duration = 30
        if (firstArg == "long") {
            duration = 180
        } else if (firstArg.isNotEmpty()) {
            firstArg.toIntOrNull()?.let {
                duration = it
            }
        }
        RingerUtils.ring(context, duration)
        transport.send(context, context.getString(R.string.cmd_ring_response))
        job?.jobFinished()
    }
}

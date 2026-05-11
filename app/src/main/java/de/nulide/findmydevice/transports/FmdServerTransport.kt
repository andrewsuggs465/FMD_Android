package de.nulide.findmydevice.transports

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import de.nulide.findmydevice.R
import de.nulide.findmydevice.commands.AccessResponse
import de.nulide.findmydevice.commands.ParserResult
import de.nulide.findmydevice.commands.hasPermission
import de.nulide.findmydevice.data.FmdLocation
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.net.FmdServerRepository
import de.nulide.findmydevice.permissions.Permission
import de.nulide.findmydevice.ui.settings.AddAccountActivity
import de.nulide.findmydevice.utils.log


class FmdServerTransport(
    val context: Context,
    private val destination: String,
) : Transport<Unit>(Unit) {

    constructor(context: Context) : this(context, "FMD Server")

    companion object {
        private val TAG = FmdServerTransport::class.simpleName
    }

    private val repo = FmdServerRepository(context).getApiService()
    private val settings = SettingsRepository.getInstance(context)

    @get:DrawableRes
    override val icon = R.drawable.ic_cloud

    @get:StringRes
    override val title = R.string.transport_fmd_server_title

    override val description = context.getString(R.string.transport_fmd_server_description)

    override val descriptionAuth = context.getString(R.string.transport_fmd_server_description_auth)

    override val descriptionNote = context.getString(R.string.transport_fmd_server_description_note)

    override val requiredPermissions = emptyList<Permission>()

    override val actions = listOf(TransportAction(R.string.Settings_Settings) { activity ->
        activity.startActivity(Intent(context, AddAccountActivity::class.java))
    })

    override fun getDestinationString() = destination

    override suspend fun isAllowed(parsed: ParserResult.Success): AccessResponse {
        val username = settings.get(Settings.SET_FMDSERVER_ID) as String
        val url = (settings.get(Settings.SET_FMDSERVER_URL) as String).toUri()
        val grantedPerms = (settings.get(Settings.SET_FMDSERVER_PERMISSIONS) as Number).toLong()

        val hasPermission = grantedPerms.hasPermission(parsed.command.permission)
        context.log().i(
            TAG,
            "Server account '$username@${url.host}' granted access to '${parsed.command.keyword}': $hasPermission"
        )
        return if (hasPermission) AccessResponse.ALLOWED else AccessResponse.DENIED_EXISTS
    }

    @SuppressLint("MissingSuperCall")
    override fun send(context: Context, msg: String) {
        //super.send(context, msg, destination)
        // not implemented for FMD Server
    }

    override fun sendNewLocation(context: Context, location: FmdLocation) {
        // no call to super(), we need to completely replace this for FMD Server
        settings.set(Settings.SET_FMDSERVER_LAST_LOCATION_UPLOAD_TIME, location.timeMillis)
        repo.sendLocation(location)
    }
}

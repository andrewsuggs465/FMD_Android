package de.nulide.findmydevice.commands

import android.content.Context
import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.nulide.findmydevice.R
import de.nulide.findmydevice.permissions.DoNotDisturbAccessPermission
import de.nulide.findmydevice.services.FmdJobService
import de.nulide.findmydevice.transports.Transport
import de.nulide.findmydevice.utils.log
import kotlinx.coroutines.CoroutineScope


class RingerModeCommand(context: Context) : Command(context) {
    companion object {
        private val TAG = RingerModeCommand::class.simpleName
    }

    override val keyword = "ringermode"
    override val usage = "ringermode [normal | vibrate | silent]"

    @get:DrawableRes
    override val icon = R.drawable.ic_vibration

    @get:StringRes
    override val shortDescription = R.string.cmd_ringermode_description_short

    override val longDescription = null

    override val requiredPermissions = listOf(DoNotDisturbAccessPermission())

    override fun <T> executeInternal(
        args: List<String>,
        transport: Transport<T>,
        coroutineScope: CoroutineScope,
        job: FmdJobService?,
    ) {
        val audioManager = context.getSystemService(AudioManager::class.java)

        val oldMode = audioManager.ringerMode

        if (args.isEmpty()) {
            val msg = context.getString(
                R.string.cmd_ringermode_response_empty,
                ringerModeToString(oldMode),
            )
            context.log().i(TAG, msg)
            transport.send(context, msg)

            job?.jobFinished()
            return
        }

        val newMode = when {
            args.contains("normal") -> AudioManager.RINGER_MODE_NORMAL
            args.contains("vibrate") -> AudioManager.RINGER_MODE_VIBRATE
            args.contains("silent") -> AudioManager.RINGER_MODE_SILENT
            else -> {
                // Do nothing. The response message will indirectly indicate that it is unchanged.
                oldMode
            }
        }

        audioManager.ringerMode = newMode

        val msg = context.getString(
            R.string.cmd_ringermode_response,
            ringerModeToString(oldMode),
            ringerModeToString(newMode)
        )
        context.log().i(TAG, msg)
        transport.send(context, msg)

        job?.jobFinished()
    }
}

fun ringerModeToString(mode: Int): String {
    // These strings are deliberately NOT translated, in order to match the command
    return when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_SILENT -> "silent"
        else -> "??"
    }
}

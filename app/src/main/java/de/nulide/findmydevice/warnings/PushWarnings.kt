package de.nulide.findmydevice.warnings

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.services.isRegisteredWithUnifiedPush
import de.nulide.findmydevice.ui.settings.FMDServerActivity
import de.nulide.findmydevice.utils.Notifications
import de.nulide.findmydevice.utils.Utils.Companion.openUrl


fun shouldWarnUnifiedPushRequired(context: Context): Boolean {
    val settings = SettingsRepository.getInstance(context)
    if (!settings.serverAccountExists()) {
        return false
    }

    return !isRegisteredWithUnifiedPush(context)
}

fun notifyWarnUnifiedPushRequired(context: Context) {
    val title = context.getString(R.string.missing_push_title)
    val text = context.getString(R.string.missing_push_description)

    Notifications.notify(
        context,
        title,
        text,
        Notifications.CHANNEL_SERVER,
        cls = FMDServerActivity::class.java,
    )
}

fun showDialogMissingUnifiedPush(context: Context, onRegisterClicked: (() -> Unit)?) {
    val builder = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.missing_push_title)
        .setMessage(R.string.missing_push_description)
        .setNeutralButton(R.string.Settings_FMDServer_Push_Open_Help, { _, _ ->
            openUrl(context, "https://fmd-foss.org/docs/fmd-android/push")
        })
        .setNegativeButton(android.R.string.cancel, { dialog, _ -> dialog.dismiss() })
        .setCancelable(false)

    if (onRegisterClicked != null) {
        builder.setPositiveButton(
            R.string.Settings_FMDServer_Push_Register,
            { dialog, _ -> onRegisterClicked() })
    }

    builder.show()
}

fun showDialogMultipleUnifiedPushDistributorApps(context: Context, onSelectClicked: () -> Unit) {
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.multiple_unified_push_distributors_title)
        .setMessage(R.string.multiple_unified_push_distributors_description)
        .setPositiveButton(R.string.select, { dialog, _ -> onSelectClicked() })
        .show()
}

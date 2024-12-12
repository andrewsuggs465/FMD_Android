package de.nulide.findmydevice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.services.FMDServerLocationUploadService
import de.nulide.findmydevice.services.FmdServerConnectivityCheckService
import de.nulide.findmydevice.services.ServerVersionCheckService
import de.nulide.findmydevice.services.TempContactExpiredService
import de.nulide.findmydevice.ui.onboarding.UpdateboardingModernCryptoActivity
import de.nulide.findmydevice.utils.log


class AppUpdatedReceiver : BroadcastReceiver() {

    companion object {
        private val TAG: String = AppUpdatedReceiver::class.java.simpleName

        const val APP_UPDATED: String = "android.intent.action.MY_PACKAGE_REPLACED"
    }

    override fun onReceive(context: Context?, intent: Intent) {
        val settings = SettingsRepository.Companion.getInstance(context!!)

        if (intent.action == APP_UPDATED) {
            context.log().i(TAG, "Running MY_PACKAGE_REPLACED (APP_UPDATED) handler")

            TempContactExpiredService.scheduleJob(context, 0)

            settings.migrateSettings()
            UpdateboardingModernCryptoActivity.notifyAboutCryptoRefreshIfRequired(context)

            if (settings.serverAccountExists()) {
                FMDServerLocationUploadService.scheduleJob(context, 0)
                FmdServerConnectivityCheckService.scheduleJob(context)
                ServerVersionCheckService.scheduleJobNow(context)
            }
        }
    }
}

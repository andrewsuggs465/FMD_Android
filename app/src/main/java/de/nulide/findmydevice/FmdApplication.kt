package de.nulide.findmydevice

import android.app.Application
import android.content.Context
import android.service.notification.StatusBarNotification
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.data.UncaughtExceptionHandler.Companion.initUncaughtExceptionHandler
import de.nulide.findmydevice.receiver.PushReceiver
import de.nulide.findmydevice.services.ServerConnectivityCheckService
import de.nulide.findmydevice.services.ServerLocationUploadService
import de.nulide.findmydevice.services.TempContactExpiredService
import de.nulide.findmydevice.ui.onboarding.PinUpdate
import de.nulide.findmydevice.ui.onboarding.UpdateboardingModernCryptoActivity
import de.nulide.findmydevice.utils.Notifications
import de.nulide.findmydevice.utils.log


class FmdApplication : Application() {

    // Workaround to "pass" this from the NotificationListenerService to the CommandExecutionWorker.
    // The problem is that we cannot pass objects between them directly.
    // But we also cannot retrieve the notification in the worker by ID,
    // because notificationManager.activeNotifications only returns the notifications posted by our own app.
    var latestStatusBarNotification: StatusBarNotification? = null

    override fun onCreate() {
        super.onCreate()

        this.log().i("FmdApplication", "Starting FmdApplication")

        Notifications.init(this)
        initUncaughtExceptionHandler(this)

        doUpdateMigrations(this)

        val settings = SettingsRepository.getInstance(this)
        if (settings.serverAccountExists()) {
            ServerLocationUploadService.scheduleRecurring(this)
            ServerConnectivityCheckService.scheduleJob(this)
        } else {
            // just in case they were still running
            ServerLocationUploadService.cancelJob(this)
            ServerConnectivityCheckService.cancelJob(this)
            PushReceiver.unregisterWithUnifiedPush(this)
        }

        TempContactExpiredService.scheduleJob(this, 0)
    }

    private fun doUpdateMigrations(context: Context) {
        val settings = SettingsRepository.getInstance(context)
        settings.migrateSettings()
        UpdateboardingModernCryptoActivity.notifyAboutCryptoRefreshIfRequired(context)
        PinUpdate.migratePin(context)
    }
}

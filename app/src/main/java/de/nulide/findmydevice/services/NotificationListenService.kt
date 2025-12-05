package de.nulide.findmydevice.services

import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.receiver.BatteryLowReceiver
import de.nulide.findmydevice.workers.CommandExecutionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class NotificationListenService : NotificationListenerService() {

    companion object {
        // LineageOS 21 / Android 14: android, BatterySaverStateMachine
        private val BATTERY_PACKAGE_NAMES = listOf("com.android.systemui", "android")
        private val BATTERY_TAGS = listOf("low_battery", "BatterySaverStateMachine")
    }

    private lateinit var settings: SettingsRepository

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        settings = SettingsRepository.getInstance(this)

        // SMS is handled separately
        val packageName = sbn.packageName
        if (packageName == Telephony.Sms.getDefaultSmsPackage(this)) {
            return
        }

        if (settings.get(Settings.SET_FMD_LOW_BAT_SEND) as Boolean
            && packageName in BATTERY_PACKAGE_NAMES
        ) {
            val tag = sbn.tag
            if (tag != null && tag in BATTERY_TAGS) {
                BatteryLowReceiver.handleLowBatteryUpload(this)
                return
            }
        }

        val messageChars = sbn.notification.extras.getCharSequence("android.text") ?: return
        val message = messageChars
            // Texts in notifications from SchildiChat start with this control character,
            // even if the message is otherwise ASCII-only (Android 16 GrapheneOS, Android 15 LineageOS).
            // HACK: Remove it, to get the notification transport working on a basic level.
            // This will break things that actually do need it (e.g., `fmd mypin lock my-message-with-rtl-text`.
            // TODO: Come back to this and think about how to properly support Unicode.
            .trimStart('\u2068')
            .toString()

        // Early sanity check + abort
        val fmdTriggerWord = (settings.get(Settings.SET_FMD_COMMAND) as String)
        if (!message.startsWith(fmdTriggerWord, ignoreCase = true)) {
            return
        }

        val inputData = workDataOf(
            CommandExecutionWorker.KEY_COMMAND to message,
            CommandExecutionWorker.KEY_TRANSPORT_TYPE to CommandExecutionWorker.TRANS_NOTIFICATION_REPLY,
            CommandExecutionWorker.KEY_DESTINATION to sbn.packageName,
            CommandExecutionWorker.KEY_NOTIF_KEY to sbn.key,
        )
        val workRequest = OneTimeWorkRequestBuilder<CommandExecutionWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }
}

package de.nulide.findmydevice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.utils.log
import de.nulide.findmydevice.workers.CommandExecutionWorker


class BatteryLowReceiver : BroadcastReceiver() {

    companion object {
        val TAG = BatteryLowReceiver::class.simpleName

        private const val MIN_INTERVAL_MILLIS = 15 * 60 * 1000 // 15 mins

        fun handleLowBatteryUpload(context: Context) {
            val settings = SettingsRepository.getInstance(context)
            context.log().i(TAG, "Handling low battery")

            if (!(settings.get(Settings.SET_FMD_LOW_BAT_SEND) as Boolean)) {
                context.log().i(TAG, "Disabled in settings, not uploading location.")
                return
            }

            // Gson quirk: Gson may interpret long values as doubles.
            // This workaround ensures that the value is interpreted as a long.
            val lastUpload = (settings.get(Settings.SET_LAST_LOW_BAT_UPLOAD) as Number).toLong()
            val now = System.currentTimeMillis()

            // If the system fires the intent or notification too often, don't upload all the time.
            // https://stackoverflow.com/questions/47969335/intent-action-battery-low-broadcast-firing-every-ten-seconds-why
            if (lastUpload + MIN_INTERVAL_MILLIS < now) {
                context.log().i(TAG, "Low battery: uploading location.")
                settings.set(Settings.SET_LAST_LOW_BAT_UPLOAD, now)
                scheduleCommand(context)
            } else {
                context.log().i(TAG, "Last low battery upload too recent, skipping.")
            }
        }

        private fun scheduleCommand(context: Context) {
            val inputData = workDataOf(
                CommandExecutionWorker.KEY_COMMAND to "locate",
                CommandExecutionWorker.KEY_TRANSPORT_TYPE to CommandExecutionWorker.TRANS_FMD_SERVER,
                CommandExecutionWorker.KEY_DESTINATION to "Low battery upload",
            )
            val workRequest = OneTimeWorkRequestBuilder<CommandExecutionWorker>()
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Theoretically, this should work.
        // Practically, ACTION_BATTERY_LOW doesn't always seem to fire??
        // Therefore, keep the notification-based low-battery approach around for now.
        if (intent.action.equals(Intent.ACTION_BATTERY_LOW)) {
            context.log().i(TAG, "Received ACTION_BATTERY_LOW")
            handleLowBatteryUpload(context)
        }
    }
}

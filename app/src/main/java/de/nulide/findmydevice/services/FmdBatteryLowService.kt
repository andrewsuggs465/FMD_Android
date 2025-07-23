package de.nulide.findmydevice.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.nulide.findmydevice.receiver.BatteryLowReceiver

class FmdBatteryLowService : FmdJobService() {

    companion object {
        const val JOB_ID: Int = 110

        @JvmStatic
        fun scheduleJobNow(context: Context) {
            if (!isRunning(context)) {
                val serviceComponent = ComponentName(context, FmdBatteryLowService::class.java)
                val builder = JobInfo.Builder(JOB_ID, serviceComponent)
                builder.setMinimumLatency(0)
                builder.setOverrideDeadline(1000)
                builder.setPersisted(true)
                builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                val jobScheduler = context.getSystemService(JobScheduler::class.java)
                jobScheduler.schedule(builder.build())
            }
        }

        @JvmStatic
        fun stopJobNow(context: Context) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.cancel(JOB_ID)
        }

        @JvmStatic
        fun isRunning(context: Context): Boolean {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            for (jobInfo in jobScheduler.allPendingJobs) {
                if (jobInfo.id == JOB_ID) {
                    return true
                }
            }
            return false
        }
    }

    // Android 8+ no longer allows registering ACTION_BATTERY_LOW in the manifest with an implicit receiver.
    // We need an active context and explicitly register the receiver at runtime.
    // Therefore, we need this job to be active in the background.
    //
    // This is ironic, because it would be more battery-efficient if the system would wake us up
    // (by calling the implicit manifest-registered receiver), instead of us having to keep a service running.
    //
    // See:
    //
    // - https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
    // - https://gitlab.com/fmd-foss/fmd-android/-/merge_requests/295
    //
    // The best way to test if this feature is working is to use the emulator in Android Studio,
    // where it is easy to change the battery level.
    private var batteryLowReceiver: BatteryLowReceiver? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        super.onStartJob(params)
        val filter = IntentFilter(Intent.ACTION_BATTERY_LOW)
        batteryLowReceiver = BatteryLowReceiver()
        registerReceiver(batteryLowReceiver, filter)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        super.onStopJob(params)
        batteryLowReceiver?.let {
            unregisterReceiver(it)
        }
        return true
    }
}
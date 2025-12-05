package de.nulide.findmydevice.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import de.nulide.findmydevice.receiver.BatteryLowReceiver
import de.nulide.findmydevice.utils.Utils

/*
 * This used to be a long-running service that keep a context open to register an intent filer
 * for ACTION_BATTERY_LOW for BatteryLowReceiver to run.
 *
 * Since this can 1) have negative battery impact, and 2) is less reliable,
 * this current implementation attempt simply schedules a periodic job that checks the
 * current battery level and exits immediately if not low.
 */
class FmdBatteryLowService : FmdJobService() {

    companion object {
        const val JOB_ID: Int = 110

        private const val INTERVAL_MILLIS = 30 * 60 * 1000L
        private const val FLEX_MILLIS = 10 * 60 * 1000L

        private const val THRESHOLD_PERCENTAGE_LOW = 20

        @JvmStatic
        fun scheduleJobNow(context: Context) {
            val serviceComponent = ComponentName(context, FmdBatteryLowService::class.java)
            val builder = JobInfo.Builder(JOB_ID, serviceComponent)
            builder.setPersisted(true)
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            builder.setPeriodic(INTERVAL_MILLIS, FLEX_MILLIS)
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.schedule(builder.build())
        }

        @JvmStatic
        fun stopJobNow(context: Context) {
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler.cancel(JOB_ID)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        super.onStartJob(params)

        val batteryLevel = Utils.getBatteryLevel(this)
        if (batteryLevel < THRESHOLD_PERCENTAGE_LOW) {
            BatteryLowReceiver.handleLowBatteryUpload(this)
        }
        jobFinished()
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        super.onStopJob(params)
        return false // let it be rescheduled using the normal period
    }
}
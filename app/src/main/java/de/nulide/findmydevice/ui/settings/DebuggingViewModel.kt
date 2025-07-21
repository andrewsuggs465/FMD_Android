package de.nulide.findmydevice.ui.settings

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nulide.findmydevice.FmdApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn


data class JobInfoExt(
    val jobInfo: JobInfo,
    val pendingReason: Int,
) {
    fun toInfoString(): String {
        return jobInfo.toInfoString() + " reason=${pendingReason.pendingJobReasonToString()}"
    }
}

fun JobInfo.toInfoString(): String {
    return "jobId=$id service=${service.flattenToShortString()} minLatencyMins=${minLatencyMillis / (60 * 1000)} maxExecutionDelayMins=${maxExecutionDelayMillis / (60 * 1000)}"
}

private data class RecentJobsItem(
    val diff: List<JobInfoExt>,
    val jobs: List<JobInfoExt>,
)

class DebuggingViewModel(
    private val jobScheduler: JobScheduler,
) : ViewModel() {

    private val _currentJobs: Flow<List<JobInfoExt>> = flow {
        while (true) {
            val jobs = jobScheduler.allPendingJobs.map {
                val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    jobScheduler.getPendingJobReason(it.id)
                } else {
                    0
                }
                JobInfoExt(it, reason)
            }
            emit(jobs)
            delay(1_500L)
        }
    }

    val currentJobs = _currentJobs.shareIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1_000L),
        replay = 1,
    )

    private val _recentJobs: Flow<List<JobInfoExt>> =
        _currentJobs.runningFold(
            initial = RecentJobsItem(emptyList<JobInfoExt>(), emptyList<JobInfoExt>())
        ) { oldItem: RecentJobsItem, newJobs: List<JobInfoExt> ->
            val newDiff = oldItem.diff + oldItem.jobs.minus(newJobs)
            RecentJobsItem(newDiff, newJobs)
        }.map { it -> it.diff }

    val recentJobs = _recentJobs.shareIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1_000L),
        replay = 1,
    )

    companion object {
        // https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-factories
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[APPLICATION_KEY] as FmdApplication)
                DebuggingViewModel(
                    jobScheduler = app.getSystemService(JobScheduler::class.java)
                )
            }
        }
    }
}

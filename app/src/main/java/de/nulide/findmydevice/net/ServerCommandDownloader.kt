package de.nulide.findmydevice.net

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.utils.Notifications
import de.nulide.findmydevice.utils.log
import de.nulide.findmydevice.workers.CommandExecutionWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServerCommandDownloader(
    private val context: Context
) {

    companion object {
        val TAG = ServerCommandDownloader::class.simpleName
    }

    private val settingsRepo = SettingsRepository.getInstance(context)

    private var tryCount = 0

    /**
     * Downloads the latest command from the server.
     * Then enqueues a WorkManager work request to execute it.
     */
    fun download() {
        if (!settingsRepo.serverAccountExists()) {
            return
        }

        val fmdServerRepo = FmdServerRepository(context).getApiService()
        fmdServerRepo.getCommand(::onResponse, ::onError)
    }

    private fun onError(error: ServerError) {
        tryCount += 1
        val shouldRetry = tryCount < 3

        val statusCode = error.statusCode ?: 0
        val msg =
            "Error downloading command: statusCode=$statusCode msg=${error.message} attempt=$tryCount retrying=$shouldRetry"
        context.log().e(TAG, msg)

        if (shouldRetry) {
            ProcessLifecycleOwner.get().lifecycleScope.launch {
                delay(1000L * tryCount)
                download()
            }
        }
    }

    private fun onResponse(remoteCommand: String) {
        if (remoteCommand.isBlank()) {
            return
        }

        if (remoteCommand.startsWith("423")) {
            showLoginBruteForceWarning()
            return
        }

        context.log().i(TAG, "Received remote command '$remoteCommand'")

        // The CommandParser needs the keyword prepended
        val commandKeyword = settingsRepo.get(Settings.SET_FMD_COMMAND).toString()
        val fullCommand = "$commandKeyword $remoteCommand"

        // Schedule work request to execute the command
        val inputData = Data.Builder()
            .putString(CommandExecutionWorker.KEY_COMMAND, fullCommand)
            .putString(
                CommandExecutionWorker.KEY_TRANSPORT_TYPE,
                CommandExecutionWorker.TRANS_FMD_SERVER
            )
            .putString(CommandExecutionWorker.KEY_DESTINATION, "FMD Server")
            .build()
        val workRequest: WorkRequest =
            OneTimeWorkRequest.Builder(CommandExecutionWorker::class.java)
                .setInputData(inputData)
                .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    private fun showLoginBruteForceWarning() {
        val account = settingsRepo.get(Settings.SET_FMDSERVER_ID) as String
        val msg: String = context.getString(R.string.server_login_attempts_text, account)
        context.log().w(TAG, msg)

        Notifications.notify(
            context,
            context.getString(R.string.server_login_attempts_title),
            msg,
            Notifications.CHANNEL_SERVER
        )
    }
}

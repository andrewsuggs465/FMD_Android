package de.nulide.findmydevice.net

import android.content.Context
import de.nulide.findmydevice.data.Settings.SET_FMDSERVER_URL
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.net.FMDServerApiRepository.Companion.MIN_REQUIRED_SERVER_VERSION
import org.apache.maven.artifact.versioning.ComparableVersion


sealed class MinRequiredVersionResult {
    data class Success(val actualVersion: String) : MinRequiredVersionResult()

    data class ServerOutdated(
        val actualVersion: String,
        val minRequiredVersion: String,
    ) : MinRequiredVersionResult()

    data class Error(val message: String) : MinRequiredVersionResult()
}

fun isMinRequiredVersion(
    context: Context,
    onResult: (MinRequiredVersionResult) -> Unit,
) {
    val settings = SettingsRepository.getInstance(context)
    val serverBaseUrl = settings.get(SET_FMDSERVER_URL) as String
    isMinRequiredVersion(context, serverBaseUrl, onResult)
}

/**
 * Query if the server version is high enough, or if the server is outdated.
 */
fun isMinRequiredVersion(
    context: Context,
    serverBaseUrl: String,
    onResult: (MinRequiredVersionResult) -> Unit,
) {
    val repo = FmdServerRepository(context)

    repo.getServerVersion(serverBaseUrl, { response: String ->
        var currentString = response
        if (currentString.startsWith("v")) {
            currentString = currentString.substring(1)
        }
        val minRequired = ComparableVersion(MIN_REQUIRED_SERVER_VERSION)
        val current = ComparableVersion(currentString)

        if (current < minRequired) {
            onResult(
                MinRequiredVersionResult.ServerOutdated(
                    currentString, MIN_REQUIRED_SERVER_VERSION
                )
            )
        } else {
            onResult(MinRequiredVersionResult.Success(currentString))
        }
    }, { error: ServerError ->
        onResult(MinRequiredVersionResult.Error("[${error.statusCode ?: 0}] ${error.message}"))
    })
}

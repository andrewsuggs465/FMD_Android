package de.nulide.findmydevice.net

import android.content.Context
import de.nulide.findmydevice.net.interceptor.UserAgentInterceptor
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

const val FMD_SERVER_PROTO_V1 = 1
const val FMD_SERVER_PROTO_V2 = 2

class FmdServerRepository(
    private val context: Context,
) {
    // private val settingsRepo = SettingsRepository.getInstance(context)

    fun getApiService(): FmdServerApiService {
        val spec = FmdServerApiV1RepoSpec(context)
        return FmdServerApiV1Repository.getInstance(spec)
    }

    /*
    fun getApiService(): FmdServerApiService {
        // TODO: Store proto version during register/login
        val accountProtocolVersion = (settingsRepo.get(Settings.SET_FMD_CRYPT_PROTO) as Number).toLong()

        if (accountProtocolVersion == FMD_SERVER_PROTO_V1) {
            val spec = FmdServerApiV1RepoSpec(context)
            return FmdServerApiV1Repository.getInstance(spec)
        } else {
            val spec = FmdServerApiV2RepoSpec(context)
            return FmdServerApiV2Repository.getInstance(spec)
        }
    }
     */

    /**
     * Gets the version of the FMD Server running at the given base URL.
     *
     * This uses the /version endpoint.
     * This should always be there, independent of the API version.
     */
    fun getServerVersion(
        baseUrl: String,
        listener: Listener<String>,
        errorListener: ErrorListener,
    ) {
        val client = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor())
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/version")
            .build()

        val callback = object : Callback {
            override fun onFailure(call: Call, e: okio.IOException) {
                errorListener.onError(e.message ?: e.stackTraceToString())
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body.string()
                if (!response.isSuccessful) {
                    errorListener.onError(
                        ServerError(
                            response.code,
                            body,
                            response.message.ifBlank { "Version check failed" },
                        )
                    )
                    return
                }
                listener.onResponse(body)
            }
        }

        client.newCall(request).enqueue(callback)
    }
}

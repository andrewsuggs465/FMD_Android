package de.nulide.findmydevice.net

import com.android.volley.AuthFailureError
import com.android.volley.NetworkError
import com.android.volley.NoConnectionError
import com.android.volley.ParseError
import com.android.volley.Response
import com.android.volley.TimeoutError
import com.android.volley.VolleyError
import de.nulide.findmydevice.data.FmdLocation

/* ------- Helper types ------- */

// https://kotlinlang.org/docs/fun-interfaces.html
fun interface Listener<T> {
    fun onResponse(response: T)

    fun into(): Response.Listener<T> {
        return Response.Listener<T> { response -> onResponse(response) }
    }
}

fun interface ErrorListener {
    fun onError(error: ServerError)

    fun onError(message: String) {
        onError(ServerError(null, null, message))
    }

    fun onError(volleyError: VolleyError) {
        onError(ServerError.fromVolleyError(volleyError))
    }

    // Inspired by Rust's Into trait
    fun into(): Response.ErrorListener {
        return Response.ErrorListener { err -> onError(ServerError.fromVolleyError(err)) }
    }
}

data class ServerError(
    val statusCode: Int?,
    val body: String?,
    val message: String,
) {
    fun likelyCause(): String {
        return when {
            statusCode == 401 -> "Unauthorized: wrong password, expired session, or invalid registration/access token."
            statusCode == 404 -> "Not found: server URL is wrong, missing /api/v1 route, or the server was reset."
            statusCode != null && statusCode in 300..399 -> "Redirect: check whether the app is using HTTP vs HTTPS and the final server URL."
            statusCode != null && statusCode >= 500 -> "Server error: check fmd-server service logs."
            message.contains("timeout", ignoreCase = true) -> "Timeout: server is unreachable or too slow to respond."
            message.contains("NoConnection", ignoreCase = true) -> "No connection: check network, DNS, VPN, and server hostname."
            else -> "Request failed before a normal success response was parsed."
        }
    }

    fun diagnosticMessage(operation: String, baseUrl: String? = null): String {
        val lines = mutableListOf<String>()
        lines.add("Operation: $operation")
        if (!baseUrl.isNullOrBlank()) {
            lines.add("Server: ${baseUrl.trimEnd('/')}")
        }
        lines.add("HTTP status: ${statusCode ?: "no HTTP response"}")
        if (!body.isNullOrBlank()) {
            val trimmedBody = body.trim()
            lines.add("Server response: ${trimmedBody.take(600)}")
        }
        if (message.isNotBlank()) {
            lines.add("Client exception: $message")
        }
        lines.add("Likely cause: ${likelyCause()}")
        return lines.joinToString("\n")
    }

    companion object {
        fun fromVolleyError(err: VolleyError): ServerError {
            val body = if (err.networkResponse != null) {
                String(err.networkResponse.data, Charsets.UTF_8)
            } else null

            val fallbackMessage = when (err) {
                is TimeoutError -> "Volley timeout while waiting for the server"
                is NoConnectionError -> "Volley could not open a network connection"
                is NetworkError -> "Volley network error"
                is AuthFailureError -> "Volley authentication failure"
                is ParseError -> "Volley could not parse the server response"
                is com.android.volley.ServerError -> "Volley server error"
                else -> body ?: err.toString()
            }

            return ServerError(
                statusCode = err.networkResponse?.statusCode,
                body,
                message = err.message ?: fallbackMessage,
            )
        }
    }
}

/* ------- The actual API service interface ------- */

interface FmdServerApiService {

    fun checkConnection(listener: Listener<Unit>, errorListener: ErrorListener)

    /* ----- Account management ----- */

    fun login(
        username: String,
        password: String,
        listener: Listener<Unit>,
        errorListener: ErrorListener,
    )

    // TODO: Explicitly revoke session
    // fun logout(listener: Listener<Unit>, errorListener: ErrorListener)

    fun register(
        requestedUsername: String,
        password: String,
        registrationToken: String,
        listener: Listener<Unit>,
        errorListener: ErrorListener,
    )

    fun unregister(listener: Listener<Unit>, errorListener: ErrorListener)

    /* ----- Account settings ----- */

    fun registerPushEndpoint(
        url: String,
        errorListener: ErrorListener,
    )

    fun changePassword(
        oldPassword: String,
        newPassword: String,
        listener: Listener<Unit>,
        errorListener: ErrorListener,
    )

    /* ----- Data ----- */
    // TODO: list of values

    fun getCommand(
        listener: Listener<String>,
        errorListener: ErrorListener,
    )

    fun sendLocation(location: FmdLocation)

    fun sendPicture(picture: String)

}

package de.nulide.findmydevice.net

import com.android.volley.Response
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
    companion object {
        fun fromVolleyError(err: VolleyError): ServerError {
            val body = if (err.networkResponse != null) {
                String(err.networkResponse.data)
            } else null

            return ServerError(
                statusCode = err.networkResponse?.statusCode,
                body,
                message = err.message ?: (body ?: ""),
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

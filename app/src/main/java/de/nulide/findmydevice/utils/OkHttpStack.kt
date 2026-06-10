package de.nulide.findmydevice.utils

import com.android.volley.AuthFailureError
import com.android.volley.Header
import com.android.volley.Request
import com.android.volley.toolbox.BaseHttpStack
import com.android.volley.toolbox.HttpResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Volley HTTP stack backed by OkHttp3.
 *
 * Replaces the default HurlStack so that all redirects (including HTTP→HTTPS cross-protocol
 * redirects) are followed transparently. OkHttp follows redirects by default.
 */
class OkHttpStack(private val client: OkHttpClient) : BaseHttpStack() {

    @Throws(IOException::class, AuthFailureError::class)
    override fun executeRequest(
        request: Request<*>,
        additionalHeaders: Map<String, String>,
    ): HttpResponse {
        val builder = okhttp3.Request.Builder().url(request.url)

        val mergedHeaders = mutableMapOf<String, String>()
        try {
            mergedHeaders.putAll(request.headers)
        } catch (_: AuthFailureError) {
        }
        mergedHeaders.putAll(additionalHeaders)
        for ((k, v) in mergedHeaders) {
            builder.header(k, v)
        }

        val contentType = request.bodyContentType?.toMediaTypeOrNull()
        val rawBody = try { request.body } catch (_: AuthFailureError) { null }
        val body = rawBody?.toRequestBody(contentType)

        when (request.method) {
            Request.Method.GET -> builder.get()
            Request.Method.POST -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            Request.Method.PUT -> builder.put(body ?: ByteArray(0).toRequestBody(null))
            Request.Method.DELETE -> if (body != null) builder.delete(body) else builder.delete()
            Request.Method.HEAD -> builder.head()
            Request.Method.PATCH -> builder.patch(body ?: ByteArray(0).toRequestBody(null))
            Request.Method.OPTIONS -> builder.method("OPTIONS", body)
            Request.Method.TRACE -> builder.method("TRACE", body)
            else -> throw IllegalStateException("Unknown Volley request method: ${request.method}")
        }

        val response = client.newCall(builder.build()).execute()

        val headers = response.headers.map { (name, value) -> Header(name, value) }
        val responseBody = response.body

        return if (responseBody != null) {
            HttpResponse(
                response.code,
                headers,
                responseBody.contentLength().toInt(),
                responseBody.byteStream(),
            )
        } else {
            HttpResponse(response.code, headers)
        }
    }
}

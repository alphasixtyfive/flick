package app.flick.tv.homeassistant

import app.flick.tv.platform.cancelAsync
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class HaTransport(private val client: OkHttpClient) {
    suspend fun request(
        credentials: HaCredentials,
        path: String,
        body: String? = null,
        maximumBytes: Int = 256 * 1024
    ): String = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val request = Request.Builder().url(credentials.url + path)
            .header("Authorization", "Bearer ${credentials.token}")
            .header("Accept", "application/json").apply {
                if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { client.cancelAsync(call) }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(HaException("Home Assistant is unavailable"))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when (it.code) {
                            in 200..299 -> Unit
                            401 -> throw HaException("Home Assistant rejected sign-in", authenticationRejected = true)
                            403 -> throw HaException("Home Assistant did not allow this request")
                            404 -> throw HaException("The selected entity is unavailable")
                            in 300..399 -> throw HaException("Check the Home Assistant server address")
                            else -> throw HaException("Home Assistant could not complete the request")
                        }
                        val responseBody = it.body ?: return@use ""
                        if (responseBody.contentLength() > maximumBytes) throw HaException("The Home Assistant response is too large")
                        val stream = responseBody.byteStream()
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (!continuation.isActive) throw IOException("Cancelled")
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > maximumBytes) throw HaException("The Home Assistant response is too large")
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString("UTF-8")
                    }
                }
                if (continuation.isActive) result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it as? HaException ?: HaException("Home Assistant is unavailable")) }
                )
            }
        })
    }
}

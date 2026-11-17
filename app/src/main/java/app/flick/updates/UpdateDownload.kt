package app.flick.updates

import app.flick.platform.cancelAsync
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Release traffic is independent of the user's Home Assistant credentials and network policy. */
internal class UpdateDownload(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) {

    suspend fun metadata(url: String): ByteArray = request(url, metadata = true) { response, active ->
        val body = response.body ?: throw UpdateException("GitHub returned an empty response.")
        if (body.contentLength() > UpdatePolicy.MAX_METADATA_BYTES) throw UpdateException("The release information is too large.")
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                if (!active()) throw IOException("Cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > UpdatePolicy.MAX_METADATA_BYTES) throw UpdateException("The release information is too large.")
                result.write(buffer, 0, count)
            }
        }
        result.toByteArray()
    }

    suspend fun apk(release: UpdateRelease, file: File, progress: (Int) -> Unit): String =
        request(release.assetUrl, metadata = false) { response, active ->
            val body = response.body ?: throw UpdateException("The update download was empty.")
            if (body.contentLength() >= 0 && body.contentLength() != release.size)
                throw UpdateException("The update download did not match this release.")
            file.outputStream().use { output ->
                body.byteStream().use { input ->
                    copyUpdate(input, output, release.size, active, progress)
                }
            }
        }

    private suspend fun <T> request(start: String, metadata: Boolean,
        read: (Response, () -> Boolean) -> T): T {
        var url = start
        repeat(5) {
            if (metadata) {
                if (!url.startsWith("https://api.github.com/repos/") || url != start)
                    throw UpdateException("The release address is not supported.")
            } else if (!UpdatePolicy.trustedDownloadUrl(url)) throw UpdateException("The update address is not supported.")
            val result = response(url, metadata, read)
            when (result) {
                is DownloadResponse.Value -> return result.value
                is DownloadResponse.Redirect -> {
                    if (metadata) throw UpdateException("The release repository has moved. Check the project page.")
                    url = url.toHttpUrl().resolve(result.location)?.toString()
                        ?: throw UpdateException("The update redirect was invalid.")
                }
            }
        }
        throw UpdateException("The update download had too many redirects.")
    }

    private suspend fun <T> response(url: String, metadata: Boolean,
        read: (Response, () -> Boolean) -> T): DownloadResponse<T> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", "Flick-Updater")
            .header("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { client.cancelAsync(call) }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(UpdateException("Could not reach GitHub. Check the connection and try again."))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        when (it.code) {
                            200 -> DownloadResponse.Value(read(it) { continuation.isActive })
                            301, 302, 303, 307, 308 -> DownloadResponse.Redirect(it.header("Location")
                                ?: throw UpdateException("The update redirect was invalid."))
                            403, 429 -> throw UpdateException("GitHub is limiting requests. Try again later.")
                            404 -> throw UpdateException("No published update was found.")
                            else -> throw UpdateException("GitHub could not complete the request. Try again later.")
                        }
                    }
                }
                if (continuation.isActive) result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it as? UpdateException
                        ?: UpdateException("The update download failed. Try again.")) },
                )
            }
        })
    }

    private sealed interface DownloadResponse<out T> {
        data class Value<T>(val value: T) : DownloadResponse<T>
        data class Redirect(val location: String) : DownloadResponse<Nothing>
    }
}

/** Caller owns the streams; a partial result is never accepted as an update. */
internal fun copyUpdate(input: InputStream, output: OutputStream, expectedSize: Long,
    active: () -> Boolean, progress: (Int) -> Unit): String {
    if (expectedSize !in 1..UpdatePolicy.MAX_APK_BYTES) throw UpdateException("The update size is not supported.")
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    var reported = -1
    val buffer = ByteArray(64 * 1024)
    while (true) {
        if (!active()) throw IOException("Cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        received += count
        if (received > expectedSize) throw UpdateException("The update download exceeded its expected size.")
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        val percent = (received * 100 / expectedSize).toInt()
        if (percent > reported) { reported = percent; progress(percent) }
    }
    if (received != expectedSize) throw UpdateException("The update download was incomplete. Try again.")
    return digest.digest().joinToString("") { "%02x".format(it) }
}

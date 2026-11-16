package app.flick.notifications

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.flick.platform.closeAsync
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** This client deliberately has no Home Assistant credentials or redirect handling. */
internal class NoticeImages {
    private val closed = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun load(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (closed.get()) return@withContext null
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return@withContext null
        val call = client.newCall(Request.Builder().url(parsed).get().build())
        calls.add(call)
        try {
            if (closed.get()) return@withContext null
            call.execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                if (body.contentLength() > MaxDownloadBytes) return@withContext null
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MaxDownloadBytes) return@withContext null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                normalize(bytes)
            }
        } catch (_: Exception) { null }
        finally { calls.remove(call) }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) client.closeAsync {
            calls.forEach { it.cancel() }
        }
    }

    companion object {
        private const val MaxDownloadBytes = 4 * 1024 * 1024
        private const val MaxSourcePixels = 16_000_000L

        private fun bounds(bytes: ByteArray): BitmapFactory.Options? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            return options.takeIf {
                it.outWidth > 0 && it.outHeight > 0 && it.outWidth.toLong() * it.outHeight <= MaxSourcePixels
            }
        }

        fun decode(bytes: ByteArray): Bitmap? {
            if (bytes.size > NoticePolicy.MaxImageBytes) return null
            val size = bounds(bytes) ?: return null
            if (size.outWidth > NoticePolicy.MaxWidth || size.outHeight > NoticePolicy.MaxHeight) return null
            return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: RuntimeException) { null }
        }

        private fun normalize(bytes: ByteArray): ByteArray? {
            val size = bounds(bytes) ?: return null
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (size.outWidth / options.inSampleSize > NoticePolicy.MaxWidth * 2 ||
                size.outHeight / options.inSampleSize > NoticePolicy.MaxHeight * 2) {
                options.inSampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            return try { encode(bitmap) } finally { bitmap.recycle() }
        }

        /** Encode a bounded JPEG for notification pictures. */
        fun encode(source: Bitmap): ByteArray? {
            val (width, height) = NoticePolicy.fitImageSize(source.width, source.height)
            var bitmap = if (source.width == width && source.height == height) source
                else Bitmap.createScaledBitmap(source, width, height, true)
            try {
                repeat(3) {
                    for (quality in intArrayOf(85, 65, 45)) {
                        val output = ByteArrayOutputStream()
                        if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
                            output.size() <= NoticePolicy.MaxImageBytes) return output.toByteArray()
                    }
                    val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4), true)
                    if (bitmap !== source) bitmap.recycle()
                    bitmap = smaller
                }
                return null
            } finally { if (bitmap !== source) bitmap.recycle() }
        }
    }
}

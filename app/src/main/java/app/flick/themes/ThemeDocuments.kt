package app.flick.themes

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A theme is copied from the chosen document; no lasting file permission is needed. */
class ThemeDocuments(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun read(uri: Uri): ThemeDefinition = runWork { signal ->
        require(uri.scheme == "content") { "Choose a theme using the file picker" }
        val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal) ?: throw IOException("Cannot open the theme file")
        descriptor.use { asset -> asset.createInputStream().use(ThemeCodec::read) }
    }

    suspend fun write(theme: ThemeDefinition, uri: Uri): Unit = runWork { signal ->
        require(uri.scheme == "content") { "Choose a destination using the file picker" }
        val bytes = ThemeCodec.write(theme).toByteArray(Charsets.UTF_8)
        require(bytes.size <= ThemeCodec.MAX_BYTES) { "Theme file is too large" }
        val descriptor = resolver.openAssetFileDescriptor(uri, "wt", signal) ?: throw IOException("Cannot create the theme file")
        descriptor.use { asset -> asset.createOutputStream().use { it.write(bytes) } }
    }

    private suspend fun <T : Any> runWork(block: (CancellationSignal) -> T): T = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val task = FutureTask<Unit>({
                try {
                    signal.throwIfCanceled()
                    val result = block(signal)
                    signal.throwIfCanceled()
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                Unit
            })
            continuation.invokeOnCancellation { signal.cancel(); task.cancel(true); worker.remove(task) }
            try { worker.execute(task) }
            catch (_: RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("The file provider is busy. Please try again."))
            }
        }
    } ?: throw IOException("The file provider took too long to respond. Please try again.")

    companion object {
        // A provider ignoring cancellation cannot accumulate background threads or jobs.
        private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "FlickThemes").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
}

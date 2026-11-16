package app.flick.platform

import java.util.concurrent.RejectedExecutionException
import okhttp3.Call
import okhttp3.OkHttpClient

/** Socket teardown can perform TLS I/O. Use the client's executor, not its caller's scope. */
internal fun OkHttpClient.closeAsync(beforeClose: () -> Unit = {}) {
    val executor = dispatcher.executorService
    try {
        executor.execute {
            try {
                beforeClose()
            } finally {
                try {
                    dispatcher.cancelAll()
                } finally {
                    try {
                        connectionPool.evictAll()
                    } finally {
                        executor.shutdown()
                    }
                }
            }
        }
    } catch (_: RejectedExecutionException) {
        // This owner's executor is already shutting down after an earlier close.
    }
}

internal fun OkHttpClient.cancelAsync(call: Call) {
    try {
        dispatcher.executorService.execute { call.cancel() }
    } catch (_: RejectedExecutionException) {
        // A queued close already cancels calls before disposing of the pool.
    }
}

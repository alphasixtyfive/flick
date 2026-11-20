package app.flick.tv.apps

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep loaded artwork until Android reports a change to installed apps or resources. */
internal class AppCatalogueCache(private val read: suspend () -> List<AppEntry>) {
    private data class Snapshot(val revision: Long, val apps: List<AppEntry>)
    private val mutex = Mutex()
    private var snapshot: Snapshot? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun invalidate() { mutableRevision.update { it + 1 } }

    suspend fun apps(): List<AppEntry> = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val requestedRevision = revision.value
        snapshot?.takeIf { it.revision == requestedRevision }?.let { return@withLock it.apps }
        val loaded = read()
        currentCoroutineContext().ensureActive()
        // A package change during the read must still cause the next caller to refresh.
        if (revision.value == requestedRevision) snapshot = Snapshot(requestedRevision, loaded)
        loaded
    }
}

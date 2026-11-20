package app.flick.tv

import android.content.Context
import android.net.Uri
import app.flick.tv.backup.BackupRepository
import app.flick.tv.backup.BackupState
import app.flick.tv.backup.BackupPreview
import app.flick.tv.backup.PreparedBackup
import app.flick.tv.backup.RestoreDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns private preview files; none of their contents enter the UI or saved-instance state. */
internal class LauncherBackups(context: Context, private val runtime: FlickRuntime) {
    private val repository = BackupRepository(context)
    private val gate = Mutex()
    private var prepared: PreparedBackup? = null
    private var draft: RestoreDraft? = null
    private var initialized = false
    private val mutable = MutableStateFlow(BackupState())
    val state = mutable.asStateFlow()

    suspend fun prepareExport() = operation {
        clearFiles()
        runtime.wallpapers.awaitLoaded()
        val next = repository.prepareExport(runtime.store.state.value, runtime.wallpapers.settings.value)
        prepared = next
        currentCoroutineContext().ensureActive()
        mutable.value = BackupState(preview = BackupPreview.Export, summary = next.summary)
    }

    suspend fun write(uri: Uri?) {
        if (uri == null) { discard(); return }
        operation {
            val export = requireNotNull(prepared) { "Create the backup again" }
            repository.write(export, uri)
            clearFiles()
            mutable.value = BackupState(message = "Backup saved")
        }
    }

    suspend fun read(uri: Uri) = operation { stage(repository.read(uri)) }

    private suspend fun stage(next: RestoreDraft) {
        val oldPrepared = prepared
        val oldDraft = draft
        prepared = null
        draft = next
        withContext(NonCancellable + Dispatchers.IO) { oldPrepared?.close(); oldDraft?.close() }
        mutable.value = BackupState(preview = BackupPreview.Restore, summary = next.summary,
            needsParentPin = next.needsParentPin(runtime.store.state.value))
    }

    suspend fun restore(parentPin: String?) = operation {
        val preview = requireNotNull(draft) { "Choose a backup first" }
        val generation = runtime.profileSession.state.value.generation
        val owner = currentCoroutineContext().job
        runtime.switchingProfile = true
        try {
            runtime.privilegedJobs.toList().filterNot { it === owner }.forEach { it.cancelAndJoin() }
            runtime.profileLock.withLock {
                check(runtime.canManage() && generation == runtime.profileSession.state.value.generation) { "Choose an adult profile again" }
                val resolved = repository.resolve(preview, runtime.store.state.value, parentPin)
                try {
                    currentCoroutineContext().ensureActive()
                    check(runtime.canManage() && generation == runtime.profileSession.state.value.generation) { "Choose an adult profile again" }
                    runtime.backupTransactions.apply(runtime, resolved)
                } finally { withContext(NonCancellable + Dispatchers.IO) { resolved.close() } }
                runCatching { repository.pruneUnusedMedia(runtime.store.state.value, runtime.wallpapers.settings.value) }
            }
            clearFiles()
            mutable.value = BackupState(message = "Backup restored. Choose a profile to continue.")
        } finally {
            runtime.switchingProfile = false
            runtime.reconcileProfileSession()
        }
    }

    suspend fun discard() {
        if (!gate.tryLock()) return
        try { clearFiles(); mutable.value = BackupState() }
        finally { gate.unlock() }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        check(gate.tryLock()) { "Another backup operation is in progress" }
        try {
            runtime.withAdultSession {
                if (!initialized) {
                    withContext(Dispatchers.IO) { repository.clearAbandonedStaging() }
                    initialized = true
                }
                mutable.value = mutable.value.copy(busy = true, message = null)
                block()
            }
        } catch (cancelled: CancellationException) {
            clearFiles()
            mutable.value = BackupState()
            throw cancelled
        }
        catch (failure: Exception) {
            mutable.value = mutable.value.copy(message = failure.message?.take(160) ?: "Backup could not be completed",
                needsParentPin = draft?.needsParentPin(runtime.store.state.value) ?: false)
            throw failure
        } finally {
            if (!runtime.canManage()) { clearFiles(); mutable.value = BackupState() }
            mutable.value = mutable.value.copy(busy = false)
            gate.unlock()
        }
    }

    private suspend fun clearFiles() = withContext(NonCancellable + Dispatchers.IO) {
        prepared?.close(); prepared = null
        draft?.close(); draft = null
    }
}

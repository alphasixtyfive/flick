package app.flick

import android.content.Context
import app.flick.backup.BackupRestoreJournal
import app.flick.backup.LocalBackupSnapshot
import app.flick.backup.ResolvedBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Two preference stores form one restore. The private journal survives process termination. */
internal class BackupTransactions(private val context: Context) {
    private val journal = BackupRestoreJournal(context)

    suspend fun apply(runtime: FlickRuntime, restored: ResolvedBackup) {
        runtime.wallpapers.awaitLoaded()
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                journal.record(snapshot(), restored.mediaDirectory)
                try {
                    restored.preferences.wallpaper?.let { runtime.wallpapers.restoreLocal(it) }
                    runtime.store.save(restored.preferences.settings)
                    journal.clear()
                    restored.keepMedia()
                } catch (failure: Exception) {
                    try {
                        journal.recover(::restoreRaw)
                        runtime.store.reloadAfterRollback()
                        runtime.wallpapers.reloadAfterRollback()
                    } catch (rollbackFailure: Exception) {
                        runtime.store.requireRecovery()
                        failure.addSuppressed(rollbackFailure)
                    }
                    throw failure
                }
            }
            runtime.profileSession.revoke()
        }
    }

    private fun snapshot(): LocalBackupSnapshot {
        val native = context.getSharedPreferences("flick_native", Context.MODE_PRIVATE)
        val wallpaper = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
        return LocalBackupSnapshot(native.getString("settings", null), wallpaper.getString("settings", null),
            wallpaper.getString("folderSnapshot", null))
    }

    private fun restoreRaw(previous: LocalBackupSnapshot) {
        val native = context.getSharedPreferences("flick_native", Context.MODE_PRIVATE)
        val wallpaper = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
        check(native.edit().putString("settings", previous.nativeSettings).commit()) { "Launcher recovery failed" }
        check(wallpaper.edit().putString("settings", previous.wallpaperSettings)
            .putString("folderSnapshot", previous.folderSnapshot).commit()) { "Wallpaper recovery failed" }
    }

    /** Runs before settings or profile authorization can be exposed. No provider or network access. */
    fun recoverBeforeStartup(): Boolean = runCatching { journal.recover(::restoreRaw); true }.getOrDefault(false)
}

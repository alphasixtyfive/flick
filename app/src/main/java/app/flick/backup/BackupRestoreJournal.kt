package app.flick.backup

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Private rollback data, never part of a portable backup. Restore these strings without provider I/O. */
data class LocalBackupSnapshot(val nativeSettings: String?, val wallpaperSettings: String?, val folderSnapshot: String?) {
    override fun toString() = "LocalBackupSnapshot(privateData=redacted)"
}

/** A crash between the two preference commits restores the previous household on next startup. */
class BackupRestoreJournal(context: Context) {
    private val base = File(context.filesDir, "backup-restore.json")
    private val atomic = AtomicFile(base)
    private val mediaRoot = File(context.filesDir, "restored-media").canonicalFile

    @Synchronized fun record(snapshot: LocalBackupSnapshot, mediaDirectory: File) {
        check(!pending()) { "Finish the previous restore before starting another" }
        val directory = mediaDirectory.canonicalFile
        require(directory.parentFile == mediaRoot && directory.name.matches(Regex("[a-f0-9-]{36}"))) { "Invalid restored-image directory" }
        val json = JSONObject().put("version", 1).put("mediaDirectory", directory.name)
            .put("nativeSettings", snapshot.nativeSettings ?: JSONObject.NULL)
            .put("wallpaperSettings", snapshot.wallpaperSettings ?: JSONObject.NULL)
            .put("folderSnapshot", snapshot.folderSnapshot ?: JSONObject.NULL)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Current settings are too large to restore safely" }
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    /** Run before repositories expose settings. Keep the marker on any failure, including a failed raw commit. */
    @Synchronized fun recover(restore: (LocalBackupSnapshot) -> Unit): Boolean {
        if (!pending()) return false
        val bytes = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            BackupArchive.copyBounded(input, output, MAX_BYTES.toLong())
            output.toByteArray()
        }
        val json = JSONObject(BackupArchive.decodeUtf8(bytes))
        check(json.opt("version") == 1 && json.keys().asSequence().toSet() == setOf("version", "mediaDirectory", "nativeSettings", "wallpaperSettings", "folderSnapshot")) { "Restore recovery record is invalid" }
        fun field(name: String): String? = json.get(name).let { value ->
            check(value == JSONObject.NULL || value is String) { "Restore recovery record is invalid" }
            value as? String
        }
        val directoryName = json.getString("mediaDirectory")
        check(directoryName.matches(Regex("[a-f0-9-]{36}"))) { "Restore recovery record is invalid" }
        val directory = File(mediaRoot, directoryName).canonicalFile
        check(directory.parentFile == mediaRoot) { "Restore recovery record is invalid" }
        restore(LocalBackupSnapshot(field("nativeSettings"), field("wallpaperSettings"), field("folderSnapshot")))
        check(!directory.exists() || directory.deleteRecursively()) { "Restored images could not be removed" }
        clear()
        return true
    }

    @Synchronized fun clear() {
        atomic.delete()
        check(!pending()) { "Restore recovery record could not be removed" }
    }

    private fun pending() = base.exists() || File(base.path + ".bak").exists()

    companion object { private const val MAX_BYTES = 4 * 1024 * 1024 }
}

package app.flick.tv.backup

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.content.FileProvider
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.wallpaper.BuiltInWallpapers
import app.flick.tv.wallpaper.WallpaperSettings
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

class PreparedBackup internal constructor(val file: File, val summary: BackupSummary, private val directory: File) : Closeable {
    override fun close() { directory.deleteRecursively() }
    override fun toString() = "PreparedBackup(privateData=redacted)"
}

class RestoreDraft internal constructor(internal val manifest: BackupManifest, internal val directory: File,
    val summary: BackupSummary) : Closeable {
    fun needsParentPin(current: LauncherSettings) = manifest.needsParentPin(current)
    override fun close() { directory.deleteRecursively() }
    override fun toString() = "RestoreDraft(privateData=redacted)"
}

class ResolvedBackup internal constructor(val preferences: BackupPreferences, val mediaDirectory: File) : Closeable {
    private var retained = false
    fun keepMedia() { retained = true }
    override fun close() { if (!retained) mediaDirectory.deleteRecursively() }
    override fun toString() = "ResolvedBackup(privateData=redacted)"
}

/** User-selected documents are the only I/O sources. No remote photos or folders are fetched. */
class BackupRepository(context: Context) {
    private val app = context.applicationContext
    private val staging = File(app.cacheDir, "backup-staging")
    private val restored = File(app.filesDir, "restored-media")
    // A provider ignoring cancellation cannot consume an unbounded number of threads.
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "FlickBackup").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

    suspend fun prepareExport(settings: LauncherSettings, wallpaper: WallpaperSettings): PreparedBackup = runWork { signal ->
        val directory = newStaging()
        try {
            val images = Images(directory, signal)
            val manifest = BackupManifest.create(settings, wallpaper, images::portable, { images.files.keys })
            val file = File(directory, "Flick-backup.zip")
            file.outputStream().use { BackupArchive.write(it, manifest, images.files, signal::throwIfCanceled) }
            require(file.length() <= BackupArchive.MAX_ARCHIVE_BYTES) { "Backup is too large" }
            PreparedBackup(file, manifest.summary(folderAvailable(manifest.folderUri)), directory)
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
    }

    suspend fun read(uri: Uri): RestoreDraft = runWork { signal ->
        require(uri.scheme == "content") { "Choose a backup using Android's file picker" }
        val directory = newStaging()
        try {
            val source = File(directory, "source")
            openDocument(uri, signal) { input -> source.outputStream().use {
                BackupArchive.copyBounded(input, it, BackupArchive.MAX_ARCHIVE_BYTES, signal::throwIfCanceled)
            } }
            val manifest = source.inputStream().use {
                BackupArchive.read(it, File(directory, "media"), ::validateImage, signal::throwIfCanceled)
            }
            source.delete()
            RestoreDraft(manifest, directory, manifest.summary(folderAvailable(manifest.folderUri)))
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
    }

    suspend fun write(prepared: PreparedBackup, destination: Uri): Unit = runWork { signal ->
        require(destination.scheme == "content") { "Choose a destination using Android's file picker" }
        signal.throwIfCanceled()
        val descriptor = app.contentResolver.openAssetFileDescriptor(destination, "wt", signal)
            ?: throw IOException("The backup destination is unavailable")
        descriptor.use { selected -> selected.createOutputStream().use { output ->
            prepared.file.inputStream().use { input ->
                BackupArchive.copyBounded(input, output, BackupArchive.MAX_ARCHIVE_BYTES, signal::throwIfCanceled)
            }
        } }
    }

    suspend fun resolve(draft: RestoreDraft, current: LauncherSettings, parentPin: String? = null): ResolvedBackup = runWork { signal ->
        require(draft.directory.isDirectory) { "Choose the backup again" }
        require(restored.isDirectory || restored.mkdirs()) { "Cannot prepare restored images" }
        val directory = File(restored, UUID.randomUUID().toString())
        check(directory.mkdir()) { "Cannot prepare restored images" }
        try {
            for (id in draft.manifest.assetIds) {
                signal.throwIfCanceled()
                File(draft.directory, "media/$id").inputStream().use { input -> File(directory, id).outputStream().use { output ->
                    BackupArchive.copyBounded(input, output, BackupArchive.MAX_IMAGE_BYTES, signal::throwIfCanceled)
                } }
            }
            val preferences = draft.manifest.resolve(current, parentPin, { id ->
                FileProvider.getUriForFile(app, "${app.packageName}.backup.media", File(directory, id)).toString()
            }, folderAvailable(draft.manifest.folderUri))
            ResolvedBackup(preferences, directory)
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
    }

    /** An abandoned preview has no authority; it is safe to clear its cache at the next process start. */
    fun clearAbandonedStaging() { staging.deleteRecursively() }

    /** Call after a successful restore has cleared its journal, using the committed settings. */
    suspend fun pruneUnusedMedia(settings: LauncherSettings, wallpaper: WallpaperSettings): Unit = runWork { signal ->
        val marker = File(app.filesDir, "backup-restore.json")
        check(!marker.exists() && !File(marker.path + ".bak").exists()) { "Finish restore recovery before removing old images" }
        val referenced = settings.profiles.flatMap { listOfNotNull(it.avatar, it.wallpaperUri) } + wallpaper.photos.map { it.uri }
        val directories = mutableSetOf<String>()
        for (reference in referenced) {
            val uri = Uri.parse(reference)
            if (uri.authority != "${app.packageName}.backup.media") continue
            val segments = uri.pathSegments
            // An unexpected internal reference is preserved rather than guessed at during cleanup.
            if (segments.size != 3 || segments[0] != "restored_images") return@runWork
            directories += segments[1]
        }
        restored.listFiles()?.forEach { directory ->
            signal.throwIfCanceled()
            if (directory.isDirectory && directory.name.matches(Regex("[a-f0-9-]{36}")) && directory.name !in directories) {
                check(directory.deleteRecursively()) { "Old restored images could not be removed" }
            }
        }
        Unit
    }

    private fun folderAvailable(value: String?): Boolean = value != null &&
        app.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == value }

    private fun newStaging(): File {
        check(staging.isDirectory || staging.mkdirs()) { "Cannot prepare backup storage" }
        return File(staging, UUID.randomUUID().toString()).also { check(it.mkdir()) { "Cannot prepare backup storage" } }
    }

    private fun <T> openDocument(uri: Uri, signal: CancellationSignal, use: (java.io.InputStream) -> T): T {
        signal.throwIfCanceled()
        val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r", signal)
            ?: throw IOException("The selected image or backup is unavailable")
        descriptor.use { return it.createInputStream().use(use) }
    }

    private inner class Images(private val directory: File, private val signal: CancellationSignal) {
        val files = linkedMapOf<String, File>()
        private val references = mutableMapOf<String, String?>()
        private var total = 0L

        fun portable(value: String): String? {
            if (BuiltInWallpapers.find(value) != null || BackupManifest.safeOnlineUri(value)) return value
            if (!value.startsWith("content://")) return null
            if (references.containsKey(value)) return references[value]
            val result = try { embed(Uri.parse(value)) }
            catch (failure: Exception) {
                signal.throwIfCanceled()
                throw IOException("A selected profile photo or wallpaper cannot be read. Choose it again before backing up.", failure)
            }
            references[value] = result
            return result
        }

        private fun embed(uri: Uri): String {
            signal.throwIfCanceled()
            val media = File(directory, "media").also { check(it.isDirectory || it.mkdirs()) }
            val temporary = File(media, "pending")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                openDocument(uri, signal) { input -> temporary.outputStream().use { output ->
                    BackupArchive.copyBounded(input, output, minOf(BackupArchive.MAX_IMAGE_BYTES,
                        BackupArchive.MAX_ARCHIVE_BYTES - total), signal::throwIfCanceled) { bytes, count -> digest.update(bytes, 0, count) }
                } }
                validateImage(temporary)
                val id = BackupArchive.hex(digest.digest())
                if (id !in files) {
                    require(files.size < BackupArchive.MAX_IMAGES) { "Too many backup images" }
                    val saved = File(media, id)
                    check(temporary.renameTo(saved)) { "Cannot save backup image" }
                    files[id] = saved
                    total += saved.length()
                }
                return BackupManifest.assetReference(id)
            } finally { temporary.delete() }
        }
    }

    private suspend fun <T : Any> runWork(block: (CancellationSignal) -> T): T = withTimeoutOrNull(120_000) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val task = FutureTask<Unit>({
                try {
                    val result = block(signal)
                    continuation.resume(result) { _, value, _ ->
                        val resource = value as? Closeable
                        if (resource != null) {
                            try { worker.execute { resource.close() } }
                            catch (_: java.util.concurrent.RejectedExecutionException) {
                                // Abandoned cache is cleared at startup; unreferenced media is pruned after restore.
                            }
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                Unit
            })
            continuation.invokeOnCancellation { signal.cancel(); task.cancel(true); worker.remove(task) }
            try { worker.execute(task) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWithException(IOException("The photo provider is busy. Please try again."))
            }
        }
    } ?: throw IOException("The file provider took too long to respond. Please try again.")

    private fun validateImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000 &&
            bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")) {
            "Backup contains an unsupported or oversized image"
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        require(decoded != null) { "Backup contains an unreadable image" }
        decoded.recycle()
    }
}

package app.flick.wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Private photo configuration; network and document reads never run on the UI thread. */
class WallpaperRepository(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val changes = Mutex()
    private val selections = Mutex()
    private val mutableSettings = MutableStateFlow(WallpaperSettings())
    private val mutableCurrent = MutableStateFlow<WallpaperPhoto?>(null)
    private val active = MutableStateFlow(false)
    private var available = emptyList<WallpaperPhoto>()
    private var folderCache = emptyList<WallpaperPhoto>()
    private var folderRefresh: Job? = null
    private val saves = AtomicLong()
    private val provider = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)) { task ->
        Thread(task, "Flick photo folder").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    val settings: StateFlow<WallpaperSettings> = mutableSettings.asStateFlow()
    val current: StateFlow<WallpaperPhoto?> = mutableCurrent.asStateFlow()

    suspend fun awaitLoaded() { loaded.await() }

    /** Restore already-validated local settings without querying an external photo provider. */
    internal suspend fun restoreLocal(value: WallpaperSettings) {
        loaded.await()
        folderRefresh?.cancelAndJoin()
        changes.withLock {
            saves.incrementAndGet()
            val clean = validate(value)
            persist(clean, if (clean.folderUri == settings.value.folderUri) folderCache else emptyList())
        }
    }

    internal suspend fun reloadAfterRollback() {
        loaded.await()
        folderRefresh?.cancelAndJoin()
        changes.withLock {
            saves.incrementAndGet()
            withContext(Dispatchers.IO) {
                val value = readSettings()
                folderCache = readFolderSnapshot(value.folderUri)
                publish(value, folderCache)
            }
        }
    }

    private val loaded = worker.async(start = CoroutineStart.LAZY) {
        changes.withLock {
            val restored = withContext(Dispatchers.IO) { readSettings() }
            folderCache = withContext(Dispatchers.IO) { readFolderSnapshot(restored.folderUri) }
            publish(restored, folderCache)
        }
    }

    init {
        worker.launch { loaded.await() }
        worker.launch {
            combine(settings, active) { value, visible -> value.intervalSeconds to visible }
                .distinctUntilChanged().collectLatest { (seconds, visible) ->
                    if (visible) {
                        loaded.await()
                        while (isActive) { delay(seconds * 1000L); advance() }
                    }
                }
        }
    }

    /** Check imported values and folder access without changing any preferences or published state. */
    suspend fun validateForImport(settings: WallpaperSettings): WallpaperSettings = withContext(Dispatchers.IO) {
        val validated = validate(settings)
        validated.folderUri?.let { scanFolder(it) }
        validated
    }

    /** Invalid input or an inaccessible newly selected folder leaves the last saved state intact. */
    suspend fun save(settings: WallpaperSettings) {
        val generation = saves.incrementAndGet()
        loaded.await()
        val validated = withContext(Dispatchers.Default) { validate(settings) }
        val cached = changes.withLock {
            folderCache.takeIf { validated.folderUri == this.settings.value.folderUri }
        }
        val folder = when {
            validated.folderUri == null -> emptyList()
            cached != null -> cached
            else -> scanFolder(validated.folderUri)
        }
        changes.withLock {
            if (generation != saves.get()) throw CancellationException("Wallpaper change superseded")
            currentCoroutineContext().ensureActive()
            persist(validated, folder)
        }
    }

    /** Remote edits use the latest restored selection and cannot replace document grants. */
    suspend fun updateOnlineSelection(transform: (WallpaperSettings) -> WallpaperSettings) {
        loaded.await()
        changes.withLock {
            val latest = settings.value
            val updated = validate(transform(latest))
            require(updated.folderUri == latest.folderUri) { "Choose photo folders on the TV" }
            saves.incrementAndGet()
            persist(updated, folderCache)
        }
    }

    suspend fun saveChanges(before: WallpaperSettings, requested: WallpaperSettings) {
        loaded.await()
        changes.withLock {
            val latest = settings.value
            val merged = validate(mergeWallpaperChanges(latest, before, requested))
            require(merged.folderUri == latest.folderUri || merged.folderUri == null) { "Choose photo folders using Android's folder picker" }
            saves.incrementAndGet()
            persist(merged, if (merged.folderUri == null) emptyList() else folderCache)
        }
    }

    /** Picker results can arrive before restoration; never merge against the initial empty state. */
    suspend fun addSelectedPhotos(uris: List<String>) = selections.withLock {
        loaded.await()
        val selected = mergeSelectedPhotos(emptyList(), uris).map { it.uri }
        if (selected.isEmpty()) return@withLock
        changes.withLock { mergeSelectedPhotos(settings.value.photos, selected) }
        val acquired = mutableListOf<Uri>()
        try {
            retainReadPermissions(selected, acquired)
            changes.withLock {
                currentCoroutineContext().ensureActive()
                val latest = settings.value
                val merged = validate(latest.copy(photos = mergeSelectedPhotos(latest.photos, selected)))
                saves.incrementAndGet()
                persist(merged, folderCache)
            }
        } catch (failure: Exception) {
            releaseUnusedReadPermissions(acquired)
            throw failure
        }
    }

    suspend fun selectFolder(value: String) = selections.withLock {
        loaded.await()
        val uri = validate(settings.value.copy(folderUri = value)).folderUri ?: throw IOException("Choose a photo folder")
        val acquired = mutableListOf<Uri>()
        try {
            retainReadPermissions(listOf(uri), acquired)
            val photos = scanFolder(uri)
            changes.withLock {
                currentCoroutineContext().ensureActive()
                val merged = validate(settings.value.copy(folderUri = uri))
                saves.incrementAndGet()
                persist(merged, photos)
            }
        } catch (failure: Exception) {
            releaseUnusedReadPermissions(acquired)
            throw failure
        }
    }

    private suspend fun retainReadPermissions(uris: List<String>, acquired: MutableList<Uri>) = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val retained = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(mutableSetOf()) { it.uri }
        uris.forEach { value ->
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(value)
            if (uri !in retained) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                acquired += uri
                retained += uri
            }
        }
    }

    private suspend fun releaseUnusedReadPermissions(acquired: List<Uri>) = withContext(NonCancellable + Dispatchers.IO) {
        val inUse = changes.withLock {
            (settings.value.photos + folderCache).mapTo(mutableSetOf()) { Uri.parse(it.uri) }.apply {
                settings.value.folderUri?.let { add(Uri.parse(it)) }
            }
        }
        // A completed commit may be followed by cancellation at the dispatcher boundary.
        acquired.filterNot { it in inUse }.forEach { uri ->
            runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Finish the local commit and its StateFlow publication together, including cancellation at return. */
    private suspend fun persist(value: WallpaperSettings, folder: List<WallpaperPhoto>) = withContext(NonCancellable + Dispatchers.IO) {
        val previousSettings = preferences.getString("settings", null)
        val previousSnapshot = preferences.getString("folderSnapshot", null)
        val edit = preferences.edit().putString("settings", encode(value).toString())
        val snapshot = folderSnapshot(value.folderUri, folder)
        if (snapshot == null) edit.remove("folderSnapshot") else edit.putString("folderSnapshot", snapshot)
        if (!edit.commit()) {
            // commit(false) still changes SharedPreferences' in-memory map.
            preferences.edit().putString("settings", previousSettings).putString("folderSnapshot", previousSnapshot).commit()
            throw IOException("Wallpaper settings could not be saved")
        }
        folderCache = folder
        publish(value, folder)
    }

    fun setActive(visible: Boolean) {
        if (active.value == visible) return
        active.value = visible
        folderRefresh?.cancel()
        if (visible) folderRefresh = worker.launch {
            loaded.await()
            val uri = settings.value.folderUri ?: return@launch
            try {
                val photos = scanFolder(uri)
                changes.withLock {
                    if (settings.value.folderUri != uri) return@withLock
                    withContext(Dispatchers.IO) {
                        preferences.edit().putString("folderSnapshot", folderSnapshot(uri, photos)).commit()
                    }
                    folderCache = photos
                    publish(settings.value, photos)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last known folder and currently displayed image. */ }
        }
    }

    fun next() { worker.launch { loaded.await(); advance() } }

    private suspend fun advance() = changes.withLock {
        mutableCurrent.value = when {
            available.isEmpty() -> null
            available.size == 1 -> available.first()
            settings.value.shuffle -> available.filter { it.uri != current.value?.uri }.random()
            else -> available[(available.indexOfFirst { it.uri == current.value?.uri } + 1) % available.size]
        }
    }

    fun close() { worker.cancel(); provider.shutdownNow() }

    private fun publish(value: WallpaperSettings, folder: List<WallpaperPhoto>) {
        available = (value.photos + folder).distinctBy { it.uri }.take(MAX_PHOTOS)
        mutableSettings.value = value
        mutableCurrent.value = available.firstOrNull { it.uri == current.value?.uri }
            ?: if (value.shuffle) available.randomOrNull() else available.firstOrNull()
    }

    private fun validate(value: WallpaperSettings): WallpaperSettings {
        require(value.photos.size <= MAX_PHOTOS) { "Choose up to $MAX_PHOTOS photos" }
        require(value.intervalSeconds in 30..86400) { "Photo interval must be between 30 seconds and one day" }
        require(value.dimAmount.isFinite() && value.dimAmount in 0f..0.7f) { "Wallpaper dimming must be between 0 and 70%" }
        val photos = value.photos.map { photo ->
            require(validPhotoUri(photo.uri)) { "Choose a built-in wallpaper, HTTP(S) image or document photo" }
            WallpaperPhoto(photo.uri.trim())
        }.distinctBy { it.uri }
        val folder = value.folderUri?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= MAX_URI && Uri.parse(it).scheme == "content" && DocumentsContract.isTreeUri(Uri.parse(it))) {
                "Choose a photo folder using Android's folder picker"
            }
        }
        val validated = value.copy(photos = photos, folderUri = folder)
        require(encode(validated).toString().length <= 3_000_000) { "This photo selection is too large" }
        return validated
    }

    private fun validPhotoUri(value: String): Boolean = validWallpaperPhotoUri(value)

    /** One level only. A provider cannot make folder traversal recursive or unbounded. */
    private suspend fun scanFolder(value: String): List<WallpaperPhoto> = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            val task = FutureTask<Unit>({
                try {
                    val photos = queryFolder(value, cancellation)
                    if (continuation.isActive) continuation.resume(photos)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                Unit
            })
            continuation.invokeOnCancellation {
                cancellation.cancel()
                task.cancel(true)
                provider.remove(task)
            }
            try { provider.execute(task) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("The photo provider is busy; try again"))
            }
        }
    } ?: throw IOException("The photo folder took too long to respond; try again")

    private fun queryFolder(value: String, cancellation: CancellationSignal): List<WallpaperPhoto> {
        cancellation.throwIfCanceled()
        val tree = Uri.parse(value)
        check(app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
            "Choose the folder again to restore photo access"
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return buildList {
            app.contentResolver.query(children, columns, null, null, null, cancellation)?.use { cursor ->
                var inspected = 0
                while (size < MAX_PHOTOS && inspected++ < 2000 && cursor.moveToNext()) {
                    cancellation.throwIfCanceled()
                    if (cursor.getString(1) !in setOf("image/jpeg", "image/png", "image/webp")) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString()
                    if (validPhotoUri(uri)) add(WallpaperPhoto(uri))
                }
            } ?: throw IOException("This photo folder is unavailable")
        }
    }

    private fun folderSnapshot(uri: String?, photos: List<WallpaperPhoto>): String? = uri?.let {
        JSONObject().put("folderUri", it).put("photos", JSONArray(photos.map { photo -> photo.uri })).toString()
    }

    private fun readFolderSnapshot(uri: String?): List<WallpaperPhoto> = runCatching {
        if (uri == null) return@runCatching emptyList()
        val raw = preferences.getString("folderSnapshot", null) ?: return@runCatching emptyList()
        if (raw.length > 3_000_000) return@runCatching emptyList()
        val snapshot = JSONObject(raw)
        if (snapshot.optString("folderUri") != uri) return@runCatching emptyList()
        val photos = snapshot.optJSONArray("photos") ?: return@runCatching emptyList()
        (0 until minOf(photos.length(), MAX_PHOTOS)).mapNotNull { index ->
            photos.optString(index).takeIf { validPhotoUri(it) && Uri.parse(it).scheme == "content" }?.let(::WallpaperPhoto)
        }.distinctBy { it.uri }
    }.getOrDefault(emptyList())

    private fun readSettings(): WallpaperSettings = runCatching {
        val raw = preferences.getString("settings", null) ?: return@runCatching BuiltInWallpapers.initialSettings()
        if (raw.length > 3_000_000) return@runCatching WallpaperSettings()
        val json = JSONObject(raw)
        val photos = json.optJSONArray("photos") ?: JSONArray()
        validate(WallpaperSettings(
            photos = (0 until minOf(photos.length(), MAX_PHOTOS)).mapNotNull { index ->
                photos.optJSONObject(index)?.let { WallpaperPhoto(it.optString("uri")) }
            },
            folderUri = json.optString("folderUri").takeIf { it.isNotBlank() },
            intervalSeconds = json.optInt("intervalSeconds", 300),
            dimAmount = json.optDouble("dimAmount", .18).toFloat(),
            shuffle = json.optBoolean("shuffle", true),
        ))
    }.getOrDefault(WallpaperSettings())

    private fun encode(value: WallpaperSettings) = JSONObject().apply {
        put("photos", JSONArray(value.photos.map { JSONObject().put("uri", it.uri) }))
        value.folderUri?.let { put("folderUri", it) }
        put("intervalSeconds", value.intervalSeconds)
        put("dimAmount", value.dimAmount.toDouble())
        put("shuffle", value.shuffle)
    }

    private companion object {
        const val MAX_PHOTOS = 500
        const val MAX_URI = 4096
    }
}

package app.flick.tv.notifications

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates notification delivery and the live camera viewer. */
class NotificationController(
    context: Context,
    private val scope: CoroutineScope,
    onNavigationFeedback: () -> Unit,
    private val cameraStream: suspend (String) -> String,
    private val canOpenCamera: () -> Boolean,
    private val onAction: (NoticeAction) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(NotificationState())
    val state: StateFlow<NotificationState> = mutableState.asStateFlow()
    private val queue = NoticeQueue()
    private val images = NoticeImages()
    private val photoLoads = mutableMapOf<String, Job>()
    private val overlay = NoticeOverlay(this.context, onNavigationFeedback)
    private val camera by lazy { CameraViewer(this.context, scope, cameraStream) }
    private enum class CameraHost { Launcher, Overlay }
    private var cameraHost: CameraHost? = null
    private var service: NotificationService? = null
    private var expiry: Job? = null
    private var rendering: Job? = null
    private var generation = 0L
    private var closed = false
    private var backgroundGeneration = 0L
    internal var backgroundRequested = false
        private set

    init { refreshPermissions() }

    fun refreshPermissions() {
        val granted = Settings.canDrawOverlays(context)
        mutableState.update { it.copy(overlayPermissionGranted = granted) }
        if (!granted) dismissNotifications()
    }

    fun overlayPermissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Called by a visible settings action, never silently from a boot receiver. */
    fun enableBackground(enabled: Boolean) {
        backgroundGeneration++
        backgroundRequested = enabled
        scope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            if (enabled) startService(Intent(context, NotificationService::class.java).setAction(NotificationService.StartBackground))
            else service?.shutdown()
        }
    }

    internal fun post(notice: TvNotice, imageUrl: String? = null) {
        val loading = imageUrl?.let { NoticePhoto.Loading() }
        val sanitized = NoticePolicy.sanitize(if (loading == null) notice else notice.copy(photo = loading)) ?: return
        scope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            refreshPermissions()
            if (!state.value.overlayPermissionGranted) {
                mutableState.update { it.copy(message = "Allow Flick to display over other apps to show notifications.") }
                return@launch
            }
            val before = queue.current
            queue.offer(sanitized)?.let { photoLoads.remove(it.id)?.cancel() }
            if (imageUrl != null && loading != null) loadPhoto(sanitized.id, loading, imageUrl)
            if (before == null || before.id == sanitized.id) presentCurrent()
        }
    }

    /** Update the same delivery in place. A newer alert with the same ID gets its own photo. */
    private fun loadPhoto(id: String, pending: NoticePhoto.Loading, url: String) {
        val load = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            try {
                val jpeg = images.load(url)
                val photo = queue.finishPhoto(id, pending, jpeg) ?: return@launch
                if (queue.current?.id != id || cameraHost != null) return@launch
                val token = generation
                val bitmap = (photo as? NoticePhoto.Ready)?.let { decodeImage(it.jpeg) }
                if (token == generation && queue.current?.photo === photo) overlay.updateImage(bitmap)
                else bitmap?.recycle()
            } finally {
                if (photoLoads[id] === owner) photoLoads.remove(id)
            }
        }
        photoLoads[id] = load
        load.start()
    }

    fun dismiss(id: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val active = queue.current?.id == id
            removeNotice(id)
            if (active) presentCurrent()
        }
    }

    fun dismissAll() = clearNotices(includeLauncherCamera = true)

    internal fun dismissNotifications() = clearNotices(includeLauncherCamera = false)

    private fun clearNotices(includeLauncherCamera: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            clearQueue()
            if (includeLauncherCamera || cameraHost == CameraHost.Overlay) hideCamera()
            presentCurrent()
        }
    }

    private fun removeNotice(id: String) {
        queue.remove(id)
        photoLoads.remove(id)?.cancel()
    }

    private fun clearQueue() {
        queue.clear()
        val loads = photoLoads.values.toList()
        photoLoads.clear()
        loads.forEach { it.cancel() }
    }

    internal fun serviceStarted(value: NotificationService) {
        if (closed) { value.shutdown(); return }
        service = value
        mutableState.update { it.copy(backgroundRunning = true, message = null) }
    }

    /** Android restarted an already-started service; this does not start another one. */
    internal fun recoverBackgroundRequest(): Long? {
        if (closed) return null
        backgroundRequested = true
        return ++backgroundGeneration
    }

    internal fun isCurrentRecovery(generation: Long) = !closed && backgroundRequested && backgroundGeneration == generation

    internal fun serviceStopped(value: NotificationService) {
        if (service !== value) return
        service = null
        mutableState.update { it.copy(backgroundRunning = false) }
        dismissNotifications()
    }

    internal fun serviceFailed() {
        mutableState.update { it.copy(message = "Android could not start the notification service. Open Flick and try again.") }
    }

    private fun startService(intent: Intent) {
        if (closed) return
        try { context.startForegroundService(intent) }
        catch (_: RuntimeException) { serviceFailed() }
    }

    internal fun closeCamera() {
        scope.launch(Dispatchers.Main.immediate) {
            if (cameraHost != null) {
                hideCamera()
                presentCurrent()
            }
        }
    }

    internal fun closeLauncherCamera() {
        if (cameraHost == CameraHost.Launcher) closeCamera()
    }

    private fun hideCamera() {
        if (cameraHost != null) {
            cameraHost = null
            camera.dismiss()
        }
    }

    private fun openCamera(notice: TvNotice, entity: String) {
        if (!canOpenCamera() || queue.current?.id != notice.id) return
        removeNotice(notice.id)
        if (!showCamera(notice.title, entity)) presentCurrent()
    }

    internal fun showCamera(title: String, entity: String, activity: Activity? = null): Boolean {
        if (closed || !canOpenCamera() || (activity == null && !Settings.canDrawOverlays(context))) return false
        cameraHost = if (activity == null) CameraHost.Overlay else CameraHost.Launcher
        presentCurrent()
        val shown = camera.show(title, entity, activity, ::closeCamera)
        if (!shown) closeCamera()
        return shown
    }

    private fun presentCurrent() {
        generation++
        expiry?.cancel()
        rendering?.cancel()
        overlay.dismiss()
        if (cameraHost != null) return
        val notice = queue.current ?: return
        val token = generation
        rendering = scope.launch(Dispatchers.Main.immediate) {
            val picture = (notice.photo as? NoticePhoto.Ready)?.let { decodeImage(it.jpeg) }
            if (token != generation) { picture?.recycle(); return@launch }
            try {
                overlay.show(notice, picture, onDismiss = { dismiss(notice.id) }, onAction = { action ->
                    val entity = action.camera
                    if (entity != null) openCamera(notice, entity)
                    else {
                        dismiss(notice.id)
                        onAction(action)
                    }
                })
            } catch (_: RuntimeException) {
                picture?.takeUnless { it.isRecycled }?.recycle()
                clearQueue()
                overlay.dismiss()
                mutableState.update { it.copy(message = "Android could not display this notification.") }
                return@launch
            }
            if (!notice.persistent) expiry = scope.launch {
                delay(notice.timeoutMillis)
                dismiss(notice.id)
            }
        }
    }

    private suspend fun decodeImage(bytes: ByteArray): Bitmap? {
        var decoded: Bitmap? = null
        try {
            withContext(Dispatchers.Default) { decoded = NoticeImages.decode(bytes) }
            return decoded
        } catch (cancelled: CancellationException) {
            decoded?.recycle()
            throw cancelled
        }
    }

    override fun close() {
        closed = true
        backgroundRequested = false
        dismissAll()
        scope.launch(Dispatchers.Main.immediate) { service?.shutdown() }
        images.close()
    }
}

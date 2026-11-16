package app.flick.notifications

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Application-scoped owner; activities only launch Android's overlay permission screen. */
class NotificationController(
    context: Context,
    private val scope: CoroutineScope,
    onNavigationFeedback: () -> Unit,
    private val onAction: (NoticeAction) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(NotificationState())
    val state: StateFlow<NotificationState> = mutableState.asStateFlow()
    private val queue = NoticeQueue()
    private val images = NoticeImages()
    private val overlay = NoticeOverlay(this.context, onNavigationFeedback)
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
        if (!granted) dismissAll()
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

    fun post(notice: TvNotice) {
        val sanitized = NoticePolicy.sanitize(notice) ?: return
        scope.launch(Dispatchers.Main.immediate) {
            if (closed) return@launch
            refreshPermissions()
            if (!state.value.overlayPermissionGranted) {
                mutableState.update { it.copy(message = "Allow Flick to display over other apps to show notifications.") }
                return@launch
            }
            val before = queue.current
            queue.offer(sanitized)
            if (before == null || before.id == sanitized.id) presentCurrent()
        }
    }

    suspend fun loadNoticeImage(url: String): ByteArray? = if (closed) null else images.load(url)

    /** A late image never resurrects an already dismissed notice or restarts its timeout. */
    fun updateImage(id: String, jpeg: ByteArray) {
        scope.launch(Dispatchers.Main.immediate) {
            if (closed || !queue.updateImage(id, jpeg) || queue.current?.id != id) return@launch
            val token = generation
            val bitmap = decodeImage(jpeg) ?: return@launch
            if (token == generation && queue.current?.id == id) overlay.updateImage(bitmap) else bitmap.recycle()
        }
    }

    fun dismiss(id: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val active = queue.current?.id == id
            queue.remove(id)
            if (active) presentCurrent()
        }
    }

    fun dismissAll() {
        scope.launch(Dispatchers.Main.immediate) {
            queue.clear()
            presentCurrent()
        }
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
        dismissAll()
    }

    internal fun serviceFailed() {
        mutableState.update { it.copy(message = "Android could not start the notification service. Open Flick and try again.") }
    }

    private fun startService(intent: Intent) {
        if (closed) return
        try { context.startForegroundService(intent) }
        catch (_: RuntimeException) { serviceFailed() }
    }

    private fun presentCurrent() {
        generation++
        expiry?.cancel()
        rendering?.cancel()
        overlay.dismiss()
        val notice = queue.current ?: return
        val token = generation
        rendering = scope.launch(Dispatchers.Main.immediate) {
            val picture = notice.imageJpeg?.let { bytes -> decodeImage(bytes) }
            if (token != generation) { picture?.recycle(); return@launch }
            try {
                overlay.show(notice, picture, onDismiss = { dismiss(notice.id) }, onAction = { action ->
                    dismiss(notice.id)
                    onAction(action)
                })
            } catch (_: RuntimeException) {
                picture?.takeUnless { it.isRecycled }?.recycle()
                queue.clear()
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

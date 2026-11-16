package app.flick.notifications

data class NoticeAction(val actionId: String, val label: String, val noticeId: String = "")

data class TvNotice(
    val id: String,
    val title: String,
    val message: String = "",
    val imageJpeg: ByteArray? = null,
    val actions: List<NoticeAction> = emptyList(),
    val persistent: Boolean = false,
    val timeoutMillis: Long = 8_000,
    val interactive: Boolean = false,
)

data class NotificationState(
    val overlayPermissionGranted: Boolean = false,
    val backgroundRunning: Boolean = false,
    val message: String? = null,
) {
    val overlayAllowed: Boolean get() = overlayPermissionGranted
}

internal object NoticePolicy {
    const val MaxImageBytes = 320 * 1024
    const val MaxWidth = 1280
    const val MaxHeight = 720
    private val identifier = Regex("[A-Za-z0-9_.:-]{1,80}")

    fun sanitize(notice: TvNotice): TvNotice? {
        if (!identifier.matches(notice.id)) return null
        val title = notice.title.trim().take(100)
        val message = notice.message.trim().take(2048)
        if (title.isBlank() && message.isBlank()) return null
        val actions = notice.actions.filter { identifier.matches(it.actionId) && it.label.isNotBlank() }
            .distinctBy { it.actionId }.take(2).map {
                it.copy(label = it.label.trim().take(32), noticeId = notice.id)
            }
        return notice.copy(
            title = title,
            message = message,
            actions = actions,
            imageJpeg = notice.imageJpeg?.takeIf { it.size <= MaxImageBytes }?.copyOf(),
            timeoutMillis = (notice.timeoutMillis.takeIf { it > 0 } ?: 8_000).coerceIn(1_000, 120_000),
            interactive = notice.interactive || notice.persistent || actions.isNotEmpty(),
        )
    }

    fun fitImageSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val ratio = minOf(1.0, MaxWidth.toDouble() / width, MaxHeight.toDouble() / height)
        return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
    }
}

/** Replacing a notice never displaces an unrelated card the viewer is reading. */
internal class NoticeQueue(private val capacity: Int = 4) {
    init { require(capacity >= 2) }
    private val notices = mutableListOf<TvNotice>()
    val current: TvNotice? get() = notices.firstOrNull()

    fun offer(notice: TvNotice) {
        val existing = notices.indexOfFirst { it.id == notice.id }
        if (existing >= 0) { notices[existing] = notice; return }
        if (notices.size == capacity) notices.removeAt(1)
        notices += notice
    }

    fun remove(id: String) { notices.removeAll { it.id == id } }
    fun updateImage(id: String, bytes: ByteArray): Boolean {
        val index = notices.indexOfFirst { it.id == id }
        if (index < 0 || bytes.size > NoticePolicy.MaxImageBytes) return false
        notices[index] = notices[index].copy(imageJpeg = bytes.copyOf())
        return true
    }
    fun clear() { notices.clear() }
}

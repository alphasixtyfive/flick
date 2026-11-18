package app.flick.notifications

data class NoticeAction(val actionId: String, val label: String, val noticeId: String = "", val camera: String? = null)

data class TvNotice(
    val id: String,
    val title: String,
    val message: String = "",
    val photo: NoticePhoto = NoticePhoto.None,
    val actions: List<NoticeAction> = emptyList(),
    val persistent: Boolean = false,
    val timeoutMillis: Long = 8_000,
    val interactive: Boolean = false,
)

sealed interface NoticePhoto {
    data object None : NoticePhoto
    class Loading : NoticePhoto
    data class Ready(val jpeg: ByteArray) : NoticePhoto
    data object Unavailable : NoticePhoto
}

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
            photo = when (val photo = notice.photo) {
                is NoticePhoto.Ready -> photoFromBytes(photo.jpeg)
                else -> photo
            },
            timeoutMillis = (notice.timeoutMillis.takeIf { it > 0 } ?: 8_000).coerceIn(1_000, 120_000),
            interactive = notice.interactive || notice.persistent || actions.isNotEmpty(),
        )
    }

    fun photoFromBytes(bytes: ByteArray?): NoticePhoto =
        if (bytes == null || bytes.size > MaxImageBytes) NoticePhoto.Unavailable else NoticePhoto.Ready(bytes.copyOf())

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

    /** Returns the replaced or evicted notice so its pending work can be cancelled. */
    fun offer(notice: TvNotice): TvNotice? {
        val existing = notices.indexOfFirst { it.id == notice.id }
        if (existing >= 0) return notices.set(existing, notice)
        val evicted = if (notices.size == capacity) notices.removeAt(1) else null
        notices += notice
        return evicted
    }

    fun remove(id: String) { notices.removeAll { it.id == id } }
    fun finishPhoto(id: String, pending: NoticePhoto.Loading, bytes: ByteArray?): NoticePhoto? {
        val index = notices.indexOfFirst { it.id == id && it.photo === pending }
        if (index < 0) return null
        val photo = NoticePolicy.photoFromBytes(bytes)
        notices[index] = notices[index].copy(photo = photo)
        return photo
    }
    fun clear() { notices.clear() }
}

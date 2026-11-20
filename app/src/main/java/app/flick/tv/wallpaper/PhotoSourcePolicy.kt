package app.flick.tv.wallpaper

import java.net.URI

internal const val MAX_CONFIGURED_PHOTOS = 500

internal fun validWallpaperPhotoUri(value: String): Boolean = runCatching {
    val clean = value.trim()
    if (clean.length !in 1..4096 || value.any(Char::isISOControl) || clean.contains('\\')) return@runCatching false
    if (BuiltInWallpapers.find(clean) != null) return@runCatching true
    val uri = URI(clean)
    uri.rawUserInfo == null && when (uri.scheme) {
        "http", "https" -> !uri.host.isNullOrBlank()
        "content" -> !uri.rawAuthority.isNullOrBlank()
        else -> false
    }
}.getOrDefault(false)

internal fun validOnlinePhotoUrl(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    value.trim().length <= 4096 && uri.scheme in listOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

/** A photo label never includes credentials, query parameters or fragments. */
internal fun photoSourceLabel(value: String): String = runCatching {
    BuiltInWallpapers.find(value)?.let { return@runCatching it.title }
    val uri = URI(value)
    if (uri.scheme !in listOf("http", "https")) return@runCatching "Selected photo"
    val host = uri.host ?: return@runCatching "Selected photo"
    val filename = uri.path.orEmpty().substringAfterLast('/').takeIf {
        it.length in 1..100 && it.none(Char::isISOControl) &&
            it.substringAfterLast('.', "").lowercase() in listOf("jpg", "jpeg", "png", "webp", "avif", "heic", "heif")
    }
    if (filename == null) host else "$filename ($host)"
}.getOrDefault("Selected photo")

/** Picker additions preserve existing remote photos and never silently truncate a collection. */
internal fun mergeSelectedPhotos(existing: List<WallpaperPhoto>, selectedUris: List<String>): List<WallpaperPhoto> {
    val selected = selectedUris.map { value ->
        val trimmed = value.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        require(trimmed.length in 1..4096 && !trimmed.any(Char::isISOControl) && !trimmed.contains('\\') &&
            uri != null && uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank()) { "Choose photos using the photo picker" }
        WallpaperPhoto(trimmed)
    }
    val combined = (existing + selected).distinctBy { it.uri }
    require(combined.size <= MAX_CONFIGURED_PHOTOS) { "Choose up to $MAX_CONFIGURED_PHOTOS photos" }
    return combined
}

internal fun photoIntervalLabel(seconds: Int): String {
    val (amount, unit) = when {
        seconds % 3600 == 0 -> seconds / 3600 to "hour"
        seconds % 60 == 0 -> seconds / 60 to "minute"
        else -> seconds to "second"
    }
    return "Every $amount $unit${if (amount == 1) "" else "s"}"
}

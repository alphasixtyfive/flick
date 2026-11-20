package app.flick.tv.wallpaper

data class WallpaperPhoto(val uri: String)

data class WallpaperSettings(
    val photos: List<WallpaperPhoto> = emptyList(),
    val folderUri: String? = null,
    val intervalSeconds: Int = 300,
    val dimAmount: Float = .18f,
    val shuffle: Boolean = true,
)

/** Only fields changed by this edit replace the latest preferences. */
internal fun mergeWallpaperChanges(current: WallpaperSettings, before: WallpaperSettings, requested: WallpaperSettings) = current.copy(
    photos = if (before.photos != requested.photos) requested.photos else current.photos,
    folderUri = if (before.folderUri != requested.folderUri) requested.folderUri else current.folderUri,
    intervalSeconds = if (before.intervalSeconds != requested.intervalSeconds) requested.intervalSeconds else current.intervalSeconds,
    dimAmount = if (before.dimAmount != requested.dimAmount) requested.dimAmount else current.dimAmount,
    shuffle = if (before.shuffle != requested.shuffle) requested.shuffle else current.shuffle,
)

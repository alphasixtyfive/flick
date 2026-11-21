package app.flick.tv.wallpaper

import app.flick.tv.BuildConfig
import app.flick.tv.R

internal data class BuiltInWallpaper(val id: String, val title: String, val drawable: Int) {
    // Resource names remain stable when Android reassigns numeric IDs in an app update.
    val uri: String get() = "android.resource://${BuildConfig.APPLICATION_ID}/drawable/flick_${id.replace('-', '_')}"
}

internal object BuiltInWallpapers {
    val items = listOf(
        BuiltInWallpaper("ivory-fold", "Ivory Fold", R.drawable.flick_ivory_fold),
        BuiltInWallpaper("midnight-ribbon", "Midnight Ribbon", R.drawable.flick_midnight_ribbon),
        BuiltInWallpaper("ember-arc", "Ember Arc", R.drawable.flick_ember_arc),
        BuiltInWallpaper("deep-current", "Deep Current", R.drawable.flick_deep_current),
        BuiltInWallpaper("sunny-block-garden", "Sunny Block Garden", R.drawable.flick_sunny_block_garden),
        BuiltInWallpaper("twilight-floating-islands", "Twilight Floating Islands", R.drawable.flick_twilight_floating_islands),
        BuiltInWallpaper("midnight-herbarium", "Midnight Herbarium", R.drawable.flick_midnight_herbarium),
        BuiltInWallpaper("fern-after-rain", "Fern After Rain", R.drawable.flick_fern_after_rain),
        BuiltInWallpaper("petal-nocturne", "Petal Nocturne", R.drawable.flick_petal_nocturne),
    )

    fun find(uri: String): BuiltInWallpaper? {
        val resource = if (uri.startsWith(legacyResourcePrefix)) {
            "android.resource://${BuildConfig.APPLICATION_ID}/" + uri.removePrefix(legacyResourcePrefix)
        } else uri
        return items.firstOrNull { it.uri == resource }
    }

    private const val legacyResourcePrefix = "android.resource://app.flick/"

    fun initialSettings() = WallpaperSettings(photos = listOf(WallpaperPhoto(items.first { it.id == "midnight-ribbon" }.uri)))
}

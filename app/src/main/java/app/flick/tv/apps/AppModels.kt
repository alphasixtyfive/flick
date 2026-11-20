package app.flick.tv.apps

import android.graphics.drawable.Drawable

data class AppEntry(val packageName: String, val name: String, val artwork: Drawable?, val system: Boolean = false,
    val artworkIsBanner: Boolean = false, val canUninstall: Boolean = false)

data class ContinueItem(val id: Long, val packageName: String, val title: String, val artwork: String?, val intentUri: String, val progress: Float = 0f)

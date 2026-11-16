package app.flick.home

import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.profiles.Profile
import app.flick.settings.LauncherSettings

object HomePolicy {
    const val MAX_FAVORITES = 100

    fun addFavorite(favorites: List<String>, packageName: String): List<String>? {
        if (packageName in favorites) return favorites
        if (favorites.size >= MAX_FAVORITES) return null
        return favorites + packageName
    }

    fun profile(settings: LauncherSettings): Profile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
        ?: Profile("locked", "Choose a profile", child = true)

    fun visibleApps(settings: LauncherSettings, apps: List<AppEntry>): List<AppEntry> {
        val profile = profile(settings)
        return if (profile.child) apps.filter { it.packageName in profile.allowedApps } else apps
    }

    fun canLaunch(settings: LauncherSettings, packageName: String): Boolean {
        val profile = profile(settings)
        return !profile.child || packageName in profile.allowedApps
    }

    fun canManage(settings: LauncherSettings): Boolean = !profile(settings).child

    fun moveFavorite(order: List<String>, installed: Set<String>, packageName: String, direction: Int): List<String> {
        val visible = order.filter { it in installed }.toMutableList()
        val index = visible.indexOf(packageName)
        val next = index + direction
        if (index >= 0 && next in visible.indices) visible.add(next, visible.removeAt(index))
        return visible
    }

    fun continueKey(item: ContinueItem): String = item.packageName + ":" + item.title.trim().lowercase(java.util.Locale.ROOT)
}

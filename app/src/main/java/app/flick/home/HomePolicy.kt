package app.flick.home

import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.profiles.Profile
import app.flick.settings.LauncherSettings
import app.flick.settings.SettingsCodec

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

    /** Ordering changes presentation only; membership still comes from the parent's grants or favorites. */
    fun homeAppPackages(settings: LauncherSettings, apps: List<AppEntry>): List<String> {
        val current = profile(settings)
        val visible = visibleApps(settings, apps).map { it.packageName }
        val base = if (current.child) visible else (settings.favorites ?: visible.take(5)).filter { it in visible }
        return (current.appOrder.filter { it in base } + base).distinct()
    }

    /** Merge only this profile's order into the latest state, retaining concurrent permission changes. */
    fun reorderApps(settings: LauncherSettings, profileId: String, order: List<String>, apps: List<AppEntry>): LauncherSettings {
        require(order.size <= MAX_FAVORITES && order.all(SettingsCodec::validPackageName)) {
            "Invalid home app order"
        }
        require(settings.activeProfileId == profileId && settings.profiles.any { it.id == profileId }) {
            "Choose this profile again"
        }
        val current = homeAppPackages(settings, apps)
        val merged = (order.filter { it in current } + current).distinct()
        return settings.copy(profiles = settings.profiles.map { if (it.id == profileId) it.copy(appOrder = merged) else it })
    }

    fun moveFavorite(order: List<String>, installed: Set<String>, packageName: String, direction: Int): List<String> {
        val visible = order.filter { it in installed }.toMutableList()
        val index = visible.indexOf(packageName)
        val next = index + direction
        if (index >= 0 && next in visible.indices) visible.add(next, visible.removeAt(index))
        return visible
    }

    fun continueKey(item: ContinueItem): String = item.packageName + ":" + item.title.trim().lowercase(java.util.Locale.ROOT)
}

package app.flick.profiles

import app.flick.settings.LauncherSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProfileStartup { Ask, ResumeLast }

data class ProfileSessionState(val authorizedProfileId: String? = null, val generation: Long = 0)

/** The saved profile is a resume preference, never proof of a current unlock. Main-thread owned. */
internal class ProfileSession(settings: LauncherSettings) {
    private var authority: Authority? = null
    private val mutable = MutableStateFlow(ProfileSessionState())
    val state = mutable.asStateFlow()

    init {
        if (startup(settings) == ProfileStartup.ResumeLast) {
            settings.profiles.find { it.id == settings.activeProfileId }?.let { authorize(settings, it, 0) }
        }
    }

    fun profile(settings: LauncherSettings): Profile? {
        val id = mutable.value.authorizedProfileId ?: return null
        if (id != settings.activeProfileId) return null
        return settings.profiles.find { it.id == id }?.takeIf { Authority.of(it) == authority }
    }

    fun canManage(settings: LauncherSettings) = profile(settings)?.child == false
    fun canLaunch(settings: LauncherSettings, packageName: String): Boolean = profile(settings)?.let {
        !it.child || packageName in it.allowedApps
    } ?: false

    /** Call only after checking the PIN against this exact profile and saving its selection. */
    fun authorize(settings: LauncherSettings, verified: Profile, generation: Long): Boolean {
        if (generation != mutable.value.generation || verified.id != settings.activeProfileId) return false
        val current = settings.profiles.find { it.id == verified.id } ?: return false
        if (Authority.of(current) != Authority.of(verified)) return false
        authority = Authority.of(current)
        mutable.value = mutable.value.copy(authorizedProfileId = current.id)
        return true
    }

    fun revoke() {
        authority = null
        mutable.value = ProfileSessionState(generation = mutable.value.generation + 1)
    }

    fun cancelSelection() { mutable.value = mutable.value.copy(generation = mutable.value.generation + 1) }

    fun revokeOnWake(settings: LauncherSettings): Boolean {
        if (startup(settings) != ProfileStartup.Ask) return false
        revoke()
        return true
    }

    /** Profile deletion, role/PIN changes or a changed selection cannot inherit an old unlock. */
    fun reconcile(settings: LauncherSettings): Boolean {
        if (mutable.value.authorizedProfileId == null || profile(settings) != null) return false
        revoke()
        return true
    }

    private data class Authority(val id: String, val child: Boolean, val salt: String?, val hash: String?) {
        override fun toString() = "ProfileAuthority(redacted)"
        companion object { fun of(profile: Profile) = Authority(profile.id, profile.child, profile.pinSalt, profile.pinHash) }
    }

    companion object {
        fun startup(settings: LauncherSettings) = settings.profileStartup
            ?: if (settings.profiles.any { it.child }) ProfileStartup.Ask else ProfileStartup.ResumeLast
    }
}

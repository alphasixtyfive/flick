package app.flick.profiles

data class Profile(val id: String, val name: String, val child: Boolean = false, val avatar: String? = null,
    val allowedApps: List<String> = emptyList(), val pinSalt: String? = null, val pinHash: String? = null,
    val wallpaperUri: String? = null) {
    override fun toString() = "Profile(child=$child, protected=${pinHash != null})"
}

package app.flick.settings

import app.flick.profiles.Profile
import app.flick.wallpaper.validWallpaperPhotoUri
import app.flick.profiles.ProfileStartup
import app.flick.weather.WeatherLocation
import org.json.JSONArray
import org.json.JSONObject

/** Imports and on-screen edits share one bounded, versioned format. */
object SettingsCodec {
    private val identifier = Regex("[A-Za-z0-9_-]{1,64}")
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val presentationFields = setOf("largeText", "compactTiles", "showHomeAssistant", "showSettings", "showWallpaper",
        "showWeather", "clock24", "showContinue", "navigationSound", "weatherLocation", "favorites", "removedContinue", "profileStartup")

    /** Apply only the user's changed preferences to the latest state, preserving profile authority. */
    fun mergePresentation(current: LauncherSettings, before: LauncherSettings, requested: LauncherSettings): LauncherSettings {
        val previous = write(before)
        val next = write(requested)
        val merged = write(current)
        for (key in presentationFields) {
            if (previous.opt(key)?.toString() == next.opt(key)?.toString()) continue
            if (next.has(key)) merged.put(key, next.get(key)) else merged.remove(key)
        }
        return read(merged)
    }

    private fun JSONArray?.strings(limit: Int) = if (this == null) emptyList() else
        (0 until minOf(length(), limit)).mapNotNull { opt(it) as? String }.distinct()
    internal fun validPackageName(value: String) = value.length <= 256 && packageName.matches(value)
    private fun packages(array: JSONArray?, limit: Int) = array.strings(limit).filter(::validPackageName)

    /** Existing private records must carry explicit authority; import defaults are not recovery. */
    fun readStored(json: JSONObject): LauncherSettings {
        val people = json.opt("profiles") as? JSONArray
        require(people != null && people.length() > 0) { "Stored profiles are missing" }
        val active = json.opt("activeProfileId") as? String
        require(!active.isNullOrBlank()) { "Stored active profile is missing" }
        return read(json)
    }

    fun read(json: JSONObject): LauncherSettings {
        require(json.optInt("version", 1) == 1) { "Unsupported settings version" }
        val people = json.optJSONArray("profiles")
        val profiles = if (people == null) listOf(Profile("home", "Home")) else {
            require(people.length() in 1..12) { "Choose between one and twelve profiles" }
            (0 until people.length()).map { index ->
                val p = people.getJSONObject(index)
                val id = p.getString("id")
                val name = p.getString("name").trim()
                require(identifier.matches(id) && name.length in 1..48 && name.none(Char::isISOControl)) { "Invalid profile" }
                val salt = p.optString("pinSalt").takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                val hash = p.optString("pinHash").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                require((salt == null) == (hash == null)) { "Incomplete profile protection" }
                Profile(id, name, p.optBoolean("child"), imageUri(p.optString("avatar")),
                    packages(p.optJSONArray("allowedApps"), 100), salt, hash,
                    wallpaperUri(p.optString("wallpaperUri")), packages(p.optJSONArray("appOrder"), 100))
            }.also { require(it.map(Profile::id).distinct().size == it.size) { "Duplicate profile IDs" } }
        }
        require(profiles.any { !it.child }) { "Keep at least one adult profile" }
        require(profiles.none { it.child } || profiles.filterNot { it.child }.all { it.pinHash != null }) {
            "Set a PIN for every adult before adding a child profile"
        }
        val active = json.optString("activeProfileId", profiles.first().id)
        require(profiles.any { it.id == active }) { "Unknown active profile" }
        val profileStartup = if (json.has("profileStartup")) requireNotNull(ProfileStartup.entries.find {
            it.name == json.getString("profileStartup")
        }) { "Invalid startup profile behavior" } else null
        require(profileStartup != ProfileStartup.Ask || profiles.filterNot { it.child }.all { it.pinHash != null }) {
            "Set a PIN for every adult before asking on startup"
        }
        val location = json.optJSONObject("weatherLocation")?.let { w ->
            val lat = w.getDouble("latitude")
            val lon = w.getDouble("longitude")
            val name = w.getString("name").trim()
            require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0 &&
                name.length in 1..100 && name.none(Char::isISOControl)) { "Invalid weather location" }
            WeatherLocation(lat, lon, name, w.optBoolean("fahrenheit"))
        }
        return LauncherSettings(
            largeText = json.optBoolean("largeText"), compactTiles = json.optBoolean("compactTiles", true),
            showHomeAssistant = json.optBoolean("showHomeAssistant", true), showSettings = json.optBoolean("showSettings", true),
            showWallpaper = json.optBoolean("showWallpaper", true), showWeather = json.optBoolean("showWeather", true),
            clock24 = json.optBoolean("clock24", true), showContinue = json.optBoolean("showContinue", true),
            navigationSound = NavigationSound.entries.firstOrNull { it.name == json.optString("navigationSound") } ?: NavigationSound.Soft,
            weatherLocation = location,
            favorites = if (json.has("favorites")) packages(json.optJSONArray("favorites"), 100) else null,
            removedContinue = json.optJSONArray("removedContinue").strings(250).filter { it.length <= 512 }.toSet(),
            profiles = profiles, activeProfileId = active,
            profileStartup = profileStartup,
        )
    }

    fun write(settings: LauncherSettings) = JSONObject().apply {
        put("version", 1)
        put("largeText", settings.largeText); put("compactTiles", settings.compactTiles)
        put("showHomeAssistant", settings.showHomeAssistant); put("showSettings", settings.showSettings); put("showWallpaper", settings.showWallpaper)
        put("showWeather", settings.showWeather); put("clock24", settings.clock24); put("showContinue", settings.showContinue)
        put("navigationSound", settings.navigationSound.name)
        settings.weatherLocation?.let { put("weatherLocation", JSONObject().put("latitude", it.latitude)
            .put("longitude", it.longitude).put("name", it.name).put("fahrenheit", it.fahrenheit)) }
        settings.favorites?.let { put("favorites", JSONArray(it)) }
        put("removedContinue", JSONArray(settings.removedContinue.toList()))
        put("activeProfileId", settings.activeProfileId)
        settings.profileStartup?.let { put("profileStartup", it.name) }
        put("profiles", JSONArray(settings.profiles.map { p -> JSONObject().put("id", p.id).put("name", p.name)
            .put("child", p.child).put("avatar", p.avatar).put("allowedApps", JSONArray(p.allowedApps))
            .put("pinSalt", p.pinSalt).put("pinHash", p.pinHash).put("wallpaperUri", p.wallpaperUri)
            .put("appOrder", JSONArray(p.appOrder)) }))
    }

    private fun wallpaperUri(value: String): String? {
        val clean = value.trim().takeIf { it.isNotEmpty() } ?: return null
        require(validWallpaperPhotoUri(clean)) { "Choose a built-in wallpaper, document photo or HTTP(S) image" }
        return clean
    }

    private fun imageUri(value: String): String? = value.takeIf { it.length <= 4096 &&
        listOf("https://", "http://", "content://").any(value::startsWith) }
}

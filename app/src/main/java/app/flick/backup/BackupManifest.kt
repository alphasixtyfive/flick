package app.flick.backup

import app.flick.profiles.Profile
import app.flick.profiles.ProfileSecurity
import app.flick.settings.LauncherSettings
import app.flick.settings.SettingsCodec
import app.flick.settings.SetupImport
import app.flick.settings.NavigationSound
import app.flick.profiles.ProfileStartup
import app.flick.themes.ThemeCodec
import app.flick.json.JsonStructure
import app.flick.wallpaper.WallpaperSettings
import app.flick.wallpaper.BuiltInWallpapers
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class BackupSummary(val profileCount: Int, val photoCount: Int, val embeddedImages: Int,
    val folderNeedsSelection: Boolean, val notices: List<String>)

/** Portable preferences contain no account, pairing or parent-PIN credentials. */
internal class BackupManifest private constructor(private val value: JSONObject) {
    val assetIds: Set<String> get() = strings(value.getJSONArray("assets")).toSet()
    val folderUri: String? get() = value.optJSONObject("wallpaper")?.optString("folderUri")?.takeIf(String::isNotBlank)

    fun summary(folderAvailable: Boolean): BackupSummary = BackupSummary(
        value.getJSONObject("settings").getJSONArray("profiles").length(),
        value.optJSONObject("wallpaper")?.optJSONArray("photos")?.length() ?: 0,
        assetIds.size, folderUri != null && !folderAvailable,
        strings(value.getJSONArray("notices")) + if (folderUri != null && !folderAvailable)
            listOf("Choose the photo folder again on this TV. Individual photos are included.") else emptyList(),
    )

    fun needsParentPin(current: LauncherSettings): Boolean {
        val settings = value.getJSONObject("settings")
        val profiles = settings.getJSONArray("profiles")
        val protectionRequired = settings.optString("profileStartup") == "Ask" ||
            (0 until profiles.length()).any { profiles.getJSONObject(it).getBoolean("child") }
        return protectionRequired && (0 until profiles.length()).any {
            val profile = profiles.getJSONObject(it)
            !profile.getBoolean("child") && localProtection(current, profile.getString("id")) == null
        }
    }

    fun resolve(current: LauncherSettings, parentPin: String?, assetUri: (String) -> String,
        folderAvailable: Boolean): SetupImport {
        val required = needsParentPin(current)
        require(!required || parentPin?.matches(Regex("[0-9]{4}")) == true) { "Set a four-digit parent PIN before restoring" }
        val settings = JSONObject(value.getJSONObject("settings").toString())
        val profiles = settings.getJSONArray("profiles")
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            if (!profile.getBoolean("child")) {
                val protected = localProtection(current, profile.getString("id")) ?: if (required)
                    ProfileSecurity.protect(Profile(profile.getString("id"), profile.getString("name")), requireNotNull(parentPin)) else null
                protected?.let { profile.put("pinSalt", it.pinSalt).put("pinHash", it.pinHash) }
            }
            resolveImage(profile, "avatar", assetUri)
            resolveImage(profile, "wallpaperUri", assetUri)
        }
        val setup = JSONObject().put("settings", settings)
        value.optJSONObject("wallpaper")?.let {
            val wallpaper = JSONObject(it.toString())
            if (!folderAvailable) wallpaper.remove("folderUri")
            val photos = wallpaper.getJSONArray("photos")
            for (index in 0 until photos.length()) resolveImage(photos.getJSONObject(index), "uri", assetUri)
            setup.put("wallpaper", wallpaper)
        }
        val parsed = SetupImport.parse(setup.toString())
        return parsed.copy(wallpaper = parsed.wallpaper?.copy(folderUri = folderUri?.takeIf { folderAvailable }))
    }

    fun encoded(): String = value.toString()
    override fun toString() = "BackupManifest(privateData=redacted)"

    companion object {
        const val MAX_MANIFEST_BYTES = 512 * 1024
        private val idPattern = Regex("[a-f0-9]{64}")
        private const val ASSET_PREFIX = "flick-asset:"
        private val forbidden = setOf("pinsalt", "pinhash", "pin", "token", "access_token", "device_secret", "devicesecret", "homeassistant")
        private val settingsFields = setOf("version", "largeText", "compactTiles", "showHomeAssistant", "showSettings", "showWallpaper", "showWeather", "clock24", "showContinue", "navigationSound", "weatherLocation", "favorites", "removedContinue", "activeProfileId", "profileStartup", "profiles", "appearance")
        private val profileFields = setOf("id", "name", "child", "avatar", "allowedApps", "wallpaperUri", "appOrder")

        fun create(settings: LauncherSettings, wallpaper: WallpaperSettings, transform: (String) -> String?,
            assets: () -> Set<String>): BackupManifest {
            val setup = JSONObject().put("settings", SettingsCodec.write(settings)).put("wallpaper", JSONObject()
                .put("photos", JSONArray(wallpaper.photos.map { JSONObject().put("uri", it.uri) }))
                .put("intervalSeconds", wallpaper.intervalSeconds).put("dimAmount", wallpaper.dimAmount)
                .put("shuffle", wallpaper.shuffle).apply { wallpaper.folderUri?.let { put("folderUri", it) } })
            return fromSetup(setup, transform, assets, legacy = false)
        }

        fun fromLegacy(text: String, transform: (String) -> String?, assets: () -> Set<String>): BackupManifest =
            fromSetup(readJson(text), transform, assets, legacy = true)

        private fun fromSetup(setup: JSONObject, transform: (String) -> String?, assets: () -> Set<String>, legacy: Boolean): BackupManifest {
            val settings = JSONObject(setup.getJSONObject("settings").toString())
            settings.keys().asSequence().toSet().toList().filter { it !in settingsFields }.forEach(settings::remove)
            if (!settings.has("profiles")) settings.put("profiles", JSONArray().put(JSONObject()
                .put("id", "home").put("name", "Home").put("child", false)))
            val profiles = settings.getJSONArray("profiles")
            var omitted = 0
            fun image(value: String): String? = transform(value).also { if (it == null) omitted++ }
            for (index in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(index)
                profile.remove("pinSalt"); profile.remove("pinHash"); profile.remove("pin")
                profile.keys().asSequence().toSet().toList().filter { it !in profileFields }.forEach(profile::remove)
                require(profile.opt("child") is Boolean) { "Each profile needs an explicit role" }
                for (field in listOf("avatar", "wallpaperUri")) {
                    val source = profile.opt(field) as? String ?: continue
                    if (source.isNotBlank()) profile.put(field, image(source))
                }
            }
            val notices = mutableListOf<String>()
            if (legacy) notices += "Home Assistant sign-in, pairing and existing adult PINs stay on this TV."
            val root = JSONObject().put("format", "flick.backup").put("version", 1).put("settings", settings)
            setup.optJSONObject("wallpaper")?.let {
                val wallpaper = JSONObject(it.toString())
                val photos = it.optJSONArray("photos") ?: JSONArray()
                require(photos.length() <= 500) { "Choose up to 500 photos" }
                val kept = JSONArray()
                for (index in 0 until photos.length()) {
                    val uri = photos.getJSONObject(index).getString("uri")
                    image(uri)?.let { kept.put(JSONObject().put("uri", it)) }
                }
                wallpaper.put("photos", kept)
                root.put("wallpaper", wallpaper)
            }
            if (omitted != 0) notices += "$omitted online or unavailable images could not be included."
            root.put("assets", JSONArray(assets().sorted())).put("notices", JSONArray(notices))
            return parse(root.toString())
        }

        fun parse(text: String): BackupManifest {
            val root = readJson(text)
            require(root.keys().asSequence().toSet() == setOf("format", "version", "settings", "assets", "notices") ||
                root.keys().asSequence().toSet() == setOf("format", "version", "settings", "wallpaper", "assets", "notices")) { "Invalid backup fields" }
            require(root.opt("format") == "flick.backup" && root.opt("version") == 1) { "Unsupported backup version" }
            rejectCredentials(root)
            val profiles = root.getJSONObject("settings").getJSONArray("profiles")
            require(root.getJSONObject("settings").keys().asSequence().toSet().all { it in settingsFields }) { "Unknown backup settings" }
            validatePresentation(root.getJSONObject("settings"))
            require(profiles.length() in 1..12) { "Invalid backup profiles" }
            val profileIds = mutableSetOf<String>()
            val usedAssets = mutableSetOf<String>()
            for (index in 0 until profiles.length()) {
                val profile = profiles.getJSONObject(index)
                require(profile.keys().asSequence().toSet().all { it in profileFields }) { "Unknown profile settings" }
                require(profile.opt("child") is Boolean) { "Each profile needs an explicit role" }
                require(profile.getString("id").matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                    profile.getString("name").trim().let { it.length in 1..48 && it.none(Char::isISOControl) }) { "Invalid backup profile" }
                listOf("allowedApps", "appOrder").forEach { field ->
                    require(!profile.has(field) || profile.opt(field) is JSONArray) { "Invalid backup app list" }
                    profile.optJSONArray(field)?.let { list ->
                        require(list.length() <= 100 && strings(list).all(SettingsCodec::validPackageName)) { "Invalid backup app list" }
                    }
                }
                require(profileIds.add(profile.getString("id"))) { "Duplicate profile IDs" }
                listOf("avatar", "wallpaperUri").forEach { field -> checkImage(profile.opt(field), usedAssets) }
            }
            require((0 until profiles.length()).any { !profiles.getJSONObject(it).getBoolean("child") }) { "Keep at least one adult profile" }
            root.getJSONObject("settings").opt("activeProfileId")?.let {
                require(it is String && it in profileIds) { "Unknown active profile" }
            }
            root.optJSONObject("wallpaper")?.let {
                require(it.keys().asSequence().toSet().all { key -> key in setOf("photos", "folderUri", "intervalSeconds", "dimAmount", "shuffle") }) { "Unknown photo settings" }
                require((!it.has("intervalSeconds") || it.opt("intervalSeconds") is Int) &&
                    (!it.has("dimAmount") || it.opt("dimAmount") is Number) &&
                    (!it.has("shuffle") || it.opt("shuffle") is Boolean)) { "Invalid photo settings" }
                require(it.optInt("intervalSeconds", 300) in 30..86400 && it.optDouble("dimAmount", .18).let { dim -> dim.isFinite() && dim in 0.0..0.7 }) { "Invalid photo settings" }
                val photos = it.getJSONArray("photos")
                require(photos.length() <= 500) { "Choose up to 500 photos" }
                for (index in 0 until photos.length()) {
                    require(photos.getJSONObject(index).keys().asSequence().toSet() == setOf("uri")) { "Invalid photo fields" }
                    require(photos.getJSONObject(index).opt("uri") is String) { "Invalid photo reference" }
                    checkImage(photos.getJSONObject(index).get("uri"), usedAssets)
                }
                it.opt("folderUri")?.takeUnless { uri -> uri == JSONObject.NULL }?.let { uri ->
                    require(uri is String && uri.length <= 4096 && uri.none(Char::isISOControl) && URI(uri).let { reference -> reference.scheme == "content" && !reference.authority.isNullOrBlank() && reference.rawUserInfo == null && reference.query == null && reference.fragment == null }) { "Invalid photo folder reference" }
                }
            }
            val assets = strings(root.getJSONArray("assets"))
            require(assets.size <= 600 && assets.size == assets.distinct().size && assets.all(idPattern::matches) && usedAssets == assets.toSet()) { "Invalid backup image references" }
            val notices = strings(root.getJSONArray("notices"))
            require(notices.size <= 20 && notices.all { it.length <= 160 && it.none(Char::isISOControl) }) { "Invalid backup notes" }
            return BackupManifest(root)
        }

        fun assetReference(id: String) = ASSET_PREFIX + id.also { require(idPattern.matches(it)) }
        fun safeOnlineUri(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 4096 && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && value.none(Char::isISOControl)
        }.getOrDefault(false)

        private fun validatePresentation(settings: JSONObject) {
            require(!settings.has("version") || settings.opt("version") == 1) { "Unsupported settings version" }
            for (field in listOf("largeText", "compactTiles", "showHomeAssistant", "showSettings", "showWallpaper", "showWeather", "clock24", "showContinue")) {
                require(!settings.has(field) || settings.opt(field) is Boolean) { "Invalid presentation setting" }
            }
            require(!settings.has("navigationSound") || NavigationSound.entries.any { it.name == settings.opt("navigationSound") }) { "Invalid navigation sound" }
            require(!settings.has("profileStartup") || ProfileStartup.entries.any { it.name == settings.opt("profileStartup") }) { "Invalid startup behavior" }
            listOf("favorites", "removedContinue").forEach { field ->
                require(!settings.has(field) || settings.opt(field) is JSONArray) { "Invalid presentation list" }
            }
            listOf("weatherLocation", "appearance").forEach { field ->
                require(!settings.has(field) || settings.opt(field) is JSONObject) { "Invalid presentation settings" }
            }
            settings.optJSONArray("favorites")?.let { require(it.length() <= 100 && strings(it).all(SettingsCodec::validPackageName)) { "Invalid favorite apps" } }
            settings.optJSONArray("removedContinue")?.let { require(it.length() <= 250 && strings(it).all { id -> id.length <= 512 }) { "Invalid Continue watching preferences" } }
            settings.optJSONObject("weatherLocation")?.let { location ->
                require(location.keys().asSequence().toSet().all { it in setOf("latitude", "longitude", "name", "fahrenheit") } &&
                    location.opt("latitude") is Number && location.opt("longitude") is Number &&
                    location.getDouble("latitude").let { it.isFinite() && it in -90.0..90.0 } &&
                    location.getDouble("longitude").let { it.isFinite() && it in -180.0..180.0 } &&
                    location.getString("name").trim().let { it.length in 1..100 && it.none(Char::isISOControl) } &&
                    (!location.has("fahrenheit") || location.opt("fahrenheit") is Boolean)) { "Invalid weather location" }
            }
            ThemeCodec.readStored(settings.optJSONObject("appearance"))
        }

        private fun checkImage(value: Any?, used: MutableSet<String>) {
            if (value == null || value == JSONObject.NULL) return
            require(value is String) { "Invalid image reference" }
            if (value.startsWith(ASSET_PREFIX)) {
                val id = value.removePrefix(ASSET_PREFIX); require(idPattern.matches(id)) { "Invalid image reference" }; used += id
            } else require(safeOnlineUri(value) || BuiltInWallpapers.find(value) != null) { "Backup image is not portable" }
        }

        private fun resolveImage(json: JSONObject, field: String, assetUri: (String) -> String) {
            val value = json.opt(field) as? String ?: return
            if (value.startsWith(ASSET_PREFIX)) json.put(field, assetUri(value.removePrefix(ASSET_PREFIX)))
        }
        private fun localProtection(current: LauncherSettings, id: String) = current.profiles.find { it.id == id && !it.child && it.pinHash != null }
        private fun strings(array: JSONArray) = (0 until array.length()).map { array.get(it).also { value -> require(value is String) } as String }
        private fun rejectCredentials(value: Any) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toSet().forEach { key -> require(key.lowercase() !in forbidden) { "Credentials are not supported in backups" }; rejectCredentials(value.get(key)) }
                is JSONArray -> (0 until value.length()).forEach { rejectCredentials(value.get(it)) }
            }
        }
        private fun readJson(text: String): JSONObject {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Backup settings are too large" }
            JsonStructure(text, maxDepth = 16, maxArrayElements = BackupArchive.MAX_IMAGES).check()
            return JSONObject(text)
        }
    }
}

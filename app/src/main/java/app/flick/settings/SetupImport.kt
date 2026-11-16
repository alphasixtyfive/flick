package app.flick.settings

import app.flick.homeassistant.HaControlSpec
import app.flick.wallpaper.WallpaperPhoto
import app.flick.wallpaper.WallpaperSettings
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONObject

data class ImportedHomeAssistant(val url: String, val token: String, val controls: List<HaControlSpec>) {
    override fun toString() = "ImportedHomeAssistant(redacted)"
}

data class SetupImport(
    val settings: LauncherSettings,
    val wallpaper: WallpaperSettings? = null,
    val homeAssistant: ImportedHomeAssistant? = null,
) {
    companion object {
        private const val MAX_BYTES = 512 * 1024

        fun read(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Setup file is too large" }
                output.write(buffer, 0, count)
            }
            return output.toString("UTF-8")
        }

        fun parse(text: String): SetupImport {
            require(text.length <= MAX_BYTES) { "Setup file is too large" }
            checkDepth(text)
            val json = JSONObject(text)
            val settings = SettingsCodec.read(json.getJSONObject("settings"))
            val wallpaper = json.optJSONObject("wallpaper")?.let { value ->
                val photos = value.optJSONArray("photos")
                require((photos?.length() ?: 0) <= 500) { "Choose up to 500 photos" }
                WallpaperSettings(
                    photos = if (photos == null) emptyList() else (0 until photos.length()).map { index ->
                        val photo = photos.getJSONObject(index)
                        WallpaperPhoto(photo.getString("uri"))
                    },
                    intervalSeconds = value.optInt("intervalSeconds", 300),
                    dimAmount = value.optDouble("dimAmount", .18).toFloat(),
                    shuffle = value.optBoolean("shuffle", true),
                )
            }
            val homeAssistant = json.optJSONObject("homeAssistant")?.let { value ->
                val controls = value.optJSONArray("controls")
                require((controls?.length() ?: 0) <= 16) { "Choose up to 16 smart-home controls" }
                ImportedHomeAssistant(value.getString("url"), value.getString("token"),
                    if (controls == null) emptyList() else (0 until controls.length()).map { index ->
                        val control = controls.getJSONObject(index)
                        HaControlSpec(control.getString("entityId"), control.getString("name"))
                    })
            }
            return SetupImport(settings, wallpaper, homeAssistant)
        }

        /** Check structure before the JSON parser can recurse into untrusted input. */
        private fun checkDepth(text: String) {
            var depth = 0
            var quoted = false
            var escaped = false
            for (character in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else when (character) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 16) { "Setup structure is too deeply nested" } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Invalid setup structure" } }
                }
            }
            require(depth == 0 && !quoted) { "Invalid setup structure" }
        }
    }
}

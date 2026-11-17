package app.flick.themes

import app.flick.json.JsonStructure
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.pow

/** Theme files contain colours only. Settings, media and credentials have a separate format. */
object ThemeCodec {
    const val MAX_BYTES = 16 * 1024
    private val roles = setOf("background", "surface", "surfaceRaised", "foreground", "secondary", "focusBackground", "focusForeground")
    private val color = Regex("#[A-Fa-f0-9]{6}")
    private val customId = Regex("custom_[a-f0-9-]{36}")

    fun read(input: InputStream): ThemeDefinition {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(output.size() < MAX_BYTES) { "Theme file is too large" }
                output.write(single)
                continue
            }
            require(output.size() + count <= MAX_BYTES) { "Theme file is too large" }
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return read(decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString())
    }

    fun read(text: String): ThemeDefinition {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Theme file is too large" }
        val jsonText = text.removePrefix("\uFEFF")
        JsonStructure(jsonText, maxDepth = 3, allowArrays = false).check()
        val parser = JSONTokener(jsonText)
        val json = parser.nextValue() as? JSONObject ?: error("Choose a Flick theme file")
        require(parser.nextClean() == 0.toChar()) { "Unexpected content after theme" }
        keys(json, setOf("format", "version", "name", "colors"))
        require(json.opt("format") == "flick-theme" && json.opt("version") == 1) { "Unsupported theme format" }
        return definition("", json)
    }

    fun write(theme: ThemeDefinition): String {
        validate(theme)
        return objectFor(theme).put("format", "flick-theme").put("version", 1).toString(2) + "\n"
    }

    /** Invalid presentation data falls back without relaxing profile or PIN validation. */
    fun readStored(value: JSONObject?): AppearanceSettings {
        if (value == null) return AppearanceSettings()
        return runCatching {
            keys(value, setOf("version", "selectedThemeId", "importedThemes"))
            require(value.opt("version") == 1)
            val imported = value.optJSONArray("importedThemes") ?: error("Imported themes are missing")
            require(imported.length() <= ThemeCatalog.MAX_IMPORTED)
            val themes = (0 until imported.length()).map { index ->
                val item = imported.getJSONObject(index)
                keys(item, setOf("id", "name", "colors"))
                val id = item.getString("id")
                require(customId.matches(id))
                definition(id, item)
            }
            validateLibrary(themes)
            val selected = value.opt("selectedThemeId") as? String ?: error("Theme selection is missing")
            AppearanceSettings(selected, themes).let {
                it.copy(selectedThemeId = ThemeCatalog.resolve(it).id)
            }
        }.getOrDefault(AppearanceSettings())
    }

    fun writeStored(settings: AppearanceSettings): JSONObject = JSONObject().apply {
        validateLibrary(settings.importedThemes)
        put("version", 1)
        put("selectedThemeId", settings.selectedThemeId)
        put("importedThemes", JSONArray(settings.importedThemes.map { objectFor(it).put("id", it.id) }))
    }

    fun validate(theme: ThemeDefinition) {
        require(theme.name.trim() == theme.name && theme.name.length in 1..40 && validUnicode(theme.name) && theme.name.none {
            it.isISOControl() || Character.getType(it) in setOf(Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
        }) { "Choose a theme name with one to forty characters" }
        val palette = theme.colors
        val surfaces = listOf(palette.background, palette.surface, palette.surfaceRaised)
        val all = surfaces + listOf(palette.foreground, palette.secondary, palette.focusBackground, palette.focusForeground)
        require(all.all { it ushr 24 == 255 }) { "Theme colours must be opaque" }
        require(surfaces.all { luminance(it) <= .12 } && luminance(palette.foreground) >= .5 && luminance(palette.secondary) >= .35) {
            "Use dark surfaces and light text so photo wallpapers stay readable"
        }
        require(surfaces.all { contrast(palette.foreground, it) >= 4.5 && contrast(palette.secondary, it) >= 4.5 }) {
            "Text needs more contrast against the theme surfaces"
        }
        require(contrast(palette.focusForeground, palette.focusBackground) >= 4.5 &&
            surfaces.all { contrast(palette.focusBackground, it) >= 3 }) { "The focus highlight needs more contrast" }
    }

    internal fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private fun luminance(value: Int): Double {
        fun channel(shift: Int): Double {
            val encoded = ((value ushr shift) and 255) / 255.0
            return if (encoded <= .04045) encoded / 12.92 else ((encoded + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    private fun definition(id: String, value: JSONObject): ThemeDefinition {
        val name = value.opt("name") as? String ?: error("Theme name is missing")
        val colors = value.opt("colors") as? JSONObject ?: error("Theme colours are missing")
        keys(colors, roles)
        fun read(role: String): Int {
            val hex = colors.opt(role) as? String ?: error("Theme colour is missing")
            require(color.matches(hex)) { "Use #RRGGBB theme colours" }
            return hex.substring(1).toLong(16).toInt() or 0xFF000000.toInt()
        }
        return ThemeDefinition(id, name, ThemePalette(read("background"), read("surface"), read("surfaceRaised"),
            read("foreground"), read("secondary"), read("focusBackground"), read("focusForeground"))).also(::validate)
    }

    private fun objectFor(theme: ThemeDefinition): JSONObject {
        val palette = theme.colors
        fun hex(value: Int) = String.format(Locale.ROOT, "#%06X", value and 0xFFFFFF)
        return JSONObject().put("name", theme.name).put("colors", JSONObject()
            .put("background", hex(palette.background)).put("surface", hex(palette.surface))
            .put("surfaceRaised", hex(palette.surfaceRaised)).put("foreground", hex(palette.foreground))
            .put("secondary", hex(palette.secondary)).put("focusBackground", hex(palette.focusBackground))
            .put("focusForeground", hex(palette.focusForeground)))
    }

    private fun keys(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().toSet() == allowed) { "Unexpected or missing theme fields" }
    }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (Character.isHighSurrogate(character)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(character)) return false
        }
        return true
    }

    private fun validateLibrary(themes: List<ThemeDefinition>) {
        require(themes.size <= ThemeCatalog.MAX_IMPORTED)
        require(themes.all { customId.matches(it.id) })
        themes.forEach(::validate)
        require(themes.map { it.id }.distinct().size == themes.size)
        val names = (ThemeCatalog.builtIn + themes).map { it.name.lowercase(Locale.ROOT) }
        require(names.distinct().size == names.size)
    }

}

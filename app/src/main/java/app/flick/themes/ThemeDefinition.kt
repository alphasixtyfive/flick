package app.flick.themes

import java.util.UUID

data class ThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val foreground: Int,
    val secondary: Int,
    val focusBackground: Int,
    val focusForeground: Int,
)

data class ThemeDefinition(val id: String, val name: String, val colors: ThemePalette)

data class AppearanceSettings(
    val selectedThemeId: String = "midnight",
    val importedThemes: List<ThemeDefinition> = emptyList(),
)

object ThemeCatalog {
    const val MAX_IMPORTED = 8
    val midnight = ThemeDefinition("midnight", "Midnight", ThemePalette(
        0xFF111720.toInt(), 0xFF1B232E.toInt(), 0xFF252F3D.toInt(),
        0xFFEDF0F5.toInt(), 0xFFB7BFCA.toInt(), 0xFFEDF0F5.toInt(), 0xFF172232.toInt(),
    ))
    val graphite = ThemeDefinition("graphite", "Graphite", ThemePalette(
        0xFF171717.toInt(), 0xFF222222.toInt(), 0xFF2D2D2D.toInt(),
        0xFFF0F0F0.toInt(), 0xFFBDBDBD.toInt(), 0xFFE2E2E2.toInt(), 0xFF191919.toInt(),
    ))
    val warm = ThemeDefinition("warm", "Warm", ThemePalette(
        0xFF201B18.toInt(), 0xFF2B2520.toInt(), 0xFF38312A.toInt(),
        0xFFF2EBE3.toInt(), 0xFFC6BAB0.toInt(), 0xFFE8D4BC.toInt(), 0xFF2A221C.toInt(),
    ))
    val builtIn = listOf(midnight, graphite, warm)

    fun themes(settings: AppearanceSettings): List<ThemeDefinition> = builtIn + settings.importedThemes

    fun resolve(settings: AppearanceSettings): ThemeDefinition =
        themes(settings).find { it.id == settings.selectedThemeId } ?: midnight

    fun select(settings: AppearanceSettings, id: String): AppearanceSettings {
        require(themes(settings).any { it.id == id }) { "Theme is unavailable" }
        return settings.copy(selectedThemeId = id)
    }

    fun addImported(settings: AppearanceSettings, theme: ThemeDefinition): AppearanceSettings {
        ThemeCodec.validate(theme)
        themes(settings).find { it.name.equals(theme.name, ignoreCase = true) && it.colors == theme.colors }?.let {
            return settings.copy(selectedThemeId = it.id)
        }
        require(themes(settings).none { it.name.equals(theme.name, ignoreCase = true) }) {
            "A different theme uses this name. Rename it or remove the old import."
        }
        require(settings.importedThemes.size < MAX_IMPORTED) { "Remove an imported theme before adding another" }
        val imported = theme.copy(id = "custom_" + UUID.randomUUID().toString())
        return settings.copy(selectedThemeId = imported.id, importedThemes = settings.importedThemes + imported)
    }

    fun remove(settings: AppearanceSettings, id: String): AppearanceSettings {
        require(settings.importedThemes.any { it.id == id }) { "Choose an imported theme" }
        return settings.copy(
            selectedThemeId = if (settings.selectedThemeId == id) midnight.id else settings.selectedThemeId,
            importedThemes = settings.importedThemes.filterNot { it.id == id },
        )
    }
}

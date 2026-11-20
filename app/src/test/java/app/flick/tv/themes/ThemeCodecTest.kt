package app.flick.tv.themes

import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeCodecTest {
    private fun theme(name: String = "My Graphite") = ThemeCatalog.graphite.copy(id = "", name = name)

    @Test fun allBuiltInPalettesMeetTheSameImportRules() {
        ThemeCatalog.builtIn.forEach { ThemeCodec.validate(it) }
    }

    @Test fun sharedThemeRoundTripsWithoutInternalIdsOrSettings() {
        val exported = ThemeCodec.write(theme())
        val restored = ThemeCodec.read(exported)
        assertEquals(theme(), restored)
        assertEquals(setOf("format", "version", "name", "colors"), JSONObject(exported).keys().asSequence().toSet())
    }

    @Test fun aUtf8FileWithBomImportsNormally() {
        val bytes = ("\uFEFF" + ThemeCodec.write(theme())).toByteArray(Charsets.UTF_8)
        assertEquals(theme(), ThemeCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test fun malformedEncodingAndOversizedFilesAreRejected() {
        assertThrows(Exception::class.java) { ThemeCodec.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.read(" ".repeat(ThemeCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            ThemeCodec.read(ByteArrayInputStream(ByteArray(ThemeCodec.MAX_BYTES + 1) { 32 }))
        }
    }

    @Test fun unknownFieldsVersionsAndNestedPayloadsCannotBecomeATheme() {
        val valid = JSONObject(ThemeCodec.write(theme()))
        listOf(
            JSONObject(valid.toString()).put("profiles", "not appearance"),
            JSONObject(valid.toString()).put("version", 2),
            JSONObject(valid.toString()).put("format", "flick-backup"),
            JSONObject(valid.toString()).put("colors", JSONObject().put("nested", JSONObject().put("extra", JSONObject()))),
        ).forEach { value -> assertThrows(Exception::class.java) { ThemeCodec.read(value.toString()) } }
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.read(valid.toString() + " trailing") }
    }

    @Test fun permissiveJsonExtensionsAndDuplicateFieldsAreRejected() {
        val valid = ThemeCodec.write(theme())
        for (invalid in listOf(
            valid.replace("\"name\": \"My Graphite\"", "'name': 'My Graphite'"),
            valid.replace("\"name\": \"My Graphite\"", "\"name\": \"My Graphite\", \"name\": \"Other\""),
            valid.replace("\"name\": \"My Graphite\"", "\"name\": \"My Graphite\", \"n\\u0061me\": \"Other\""),
            valid.trimEnd().dropLast(1) + ",}",
        )) assertThrows(Exception::class.java) { ThemeCodec.read(invalid) }
    }

    @Test fun colorsMustBeOpaqueHexStringsAndComplete() {
        for (invalid in listOf("#88112233", "transparent", "url(https://example.test)", "#123")) {
            val json = JSONObject(ThemeCodec.write(theme()))
            json.getJSONObject("colors").put("background", invalid)
            assertThrows(IllegalArgumentException::class.java) { ThemeCodec.read(json.toString()) }
        }
        val missing = JSONObject(ThemeCodec.write(theme()))
        missing.getJSONObject("colors").remove("secondary")
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.read(missing.toString()) }
    }

    @Test fun lowContrastTextAndFocusAreRejected() {
        val original = theme()
        listOf(
            original.colors.copy(secondary = original.colors.background),
            original.colors.copy(foreground = original.colors.surface),
            original.colors.copy(focusForeground = original.colors.focusBackground),
            original.colors.copy(focusBackground = original.colors.surface),
        ).forEach { colors ->
            assertThrows(IllegalArgumentException::class.java) { ThemeCodec.validate(original.copy(colors = colors)) }
        }
        assertEquals(21.0, ThemeCodec.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), .0001)
    }

    @Test fun lightPanelsAreRejectedEvenWhenTheirOwnTextContrastPasses() {
        val colors = theme().colors.copy(background = -1, surface = -1, surfaceRaised = -1,
            foreground = 0xFF000000.toInt(), secondary = 0xFF333333.toInt())
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.validate(theme().copy(colors = colors)) }
    }

    @Test fun themeNamesCannotContainControlOrDirectionalCharacters() {
        for (invalid in listOf("", " leading", "trailing ", "x".repeat(41), "line\nname", "hidden\u202ename", "bad\uD800", "bad\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) { ThemeCodec.validate(theme(invalid)) }
        }
    }

    @Test fun importingAnExistingPaletteDoesNotConsumeLibrarySlots() {
        val initial = AppearanceSettings()
        assertEquals(initial.copy(selectedThemeId = "graphite"), ThemeCatalog.addImported(initial, ThemeCatalog.graphite))
        val imported = ThemeCatalog.addImported(initial, theme())
        assertEquals(imported, ThemeCatalog.addImported(imported, theme()))
    }

    @Test fun importsAreBoundedAndRemovalKeepsAValidSelection() {
        var settings = AppearanceSettings()
        repeat(ThemeCatalog.MAX_IMPORTED) { settings = ThemeCatalog.addImported(settings, theme("Theme $it")) }
        assertThrows(IllegalArgumentException::class.java) { ThemeCatalog.addImported(settings, theme("Another")) }
        val removed = ThemeCatalog.remove(settings, settings.selectedThemeId)
        assertEquals("midnight", removed.selectedThemeId)
        assertEquals(ThemeCatalog.MAX_IMPORTED - 1, removed.importedThemes.size)
        assertThrows(IllegalArgumentException::class.java) { ThemeCatalog.remove(removed, "midnight") }
    }

    @Test fun editedSameNameCannotSilentlyReplaceAnExistingTheme() {
        val saved = ThemeCatalog.addImported(AppearanceSettings(), theme())
        val changed = theme().copy(colors = ThemeCatalog.warm.colors)
        assertThrows(IllegalArgumentException::class.java) { ThemeCatalog.addImported(saved, changed) }
        assertEquals(1, saved.importedThemes.size)
        assertEquals(theme().colors, ThemeCatalog.resolve(saved).colors)
    }

    @Test fun storedSelectionAndLibraryRoundTripAndUnknownSelectionFallsBack() {
        val saved = ThemeCatalog.addImported(AppearanceSettings(), theme())
        assertEquals(saved, ThemeCodec.readStored(ThemeCodec.writeStored(saved)))
        val missing = ThemeCodec.writeStored(saved).put("selectedThemeId", "not-installed")
        val restored = ThemeCodec.readStored(missing)
        assertEquals("midnight", restored.selectedThemeId)
        assertEquals(saved.importedThemes, restored.importedThemes)
    }
}

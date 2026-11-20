package app.flick.tv.settings

import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileSecurity
import app.flick.tv.profiles.ProfileStartup
import app.flick.tv.weather.WeatherLocation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsCodecTest {
    @Test fun settingsRoundTripWithoutLosingVisibilityOrLocation() {
        val settings = LauncherSettings(showSettings = false, showHomeAssistant = false,
            weatherLocation = WeatherLocation(51.5, -.1, "London"), favorites = emptyList())
        assertEquals(settings, SettingsCodec.read(SettingsCodec.write(settings)))
    }

    @Test fun importsRejectInvalidCoordinatesAndUnknownProfiles() {
        val json = SettingsCodec.write(LauncherSettings())
        json.put("weatherLocation", JSONObject().put("latitude", 91).put("longitude", 0).put("name", "Example"))
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(json) }
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(JSONObject().put("activeProfileId", "missing")) }
    }

    @Test fun addingAChildRequiresProtectedAdults() {
        val settings = LauncherSettings(profiles = listOf(Profile("home", "Home"), Profile("child", "Child", true)))
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(SettingsCodec.write(settings)) }
        val protected = ProfileSecurity.protect(settings.profiles[0], "1234")
        assertEquals(2, SettingsCodec.read(SettingsCodec.write(settings.copy(profiles = listOf(protected, settings.profiles[1])))).profiles.size)
    }

    @Test fun profileProtectionUsesDistinctSaltsAndRejectsMalformedPins() {
        val profile = Profile("home", "Home")
        val first = ProfileSecurity.protect(profile, "1234")
        val second = ProfileSecurity.protect(profile, "1234")
        assertNotEquals(first.pinSalt, second.pinSalt)
        assertNotEquals(first.pinHash, second.pinHash)
        assertThrows(IllegalArgumentException::class.java) { ProfileSecurity.protect(profile, "12") }
    }

    @Test fun packageListsRejectIntentAndPathPayloads() {
        val json = SettingsCodec.write(LauncherSettings(favorites = listOf("org.example.video", "../private", "intent://payload")))
        assertEquals(listOf("org.example.video"), SettingsCodec.read(json).favorites)
    }

    @Test fun staleAppearanceEditsCannotRestoreAnAdultOrOverwriteOtherChanges() {
        val adult = ProfileSecurity.protect(Profile("home", "Home"), "1234")
        val before = LauncherSettings(profiles = listOf(adult, Profile("child", "Child", true)))
        val latest = before.copy(activeProfileId = "child", showHomeAssistant = false)
        val edited = before.copy(showSettings = false)
        val merged = SettingsCodec.mergePresentation(latest, before, edited)
        assertEquals("child", merged.activeProfileId)
        assertFalse(merged.showHomeAssistant)
        assertFalse(merged.showSettings)
        assertEquals(latest.profiles, merged.profiles)
    }

    @Test fun explicitPresentationClearsWinWhileUntouchedConcurrentValuesSurvive() {
        val before = LauncherSettings(weatherLocation = WeatherLocation(51.5, -.1, "London"),
            favorites = listOf("org.example.video"))
        val current = before.copy(clock24 = false, navigationSound = NavigationSound.System,
            removedContinue = setOf("org.example.video:finished"), favorites = listOf("org.example.game"))
        val requested = before.copy(weatherLocation = null, favorites = null, navigationSound = NavigationSound.Off)
        val merged = SettingsCodec.mergePresentation(current, before, requested)
        assertEquals(current.copy(weatherLocation = null, favorites = null, navigationSound = NavigationSound.Off), merged)
    }

    @Test fun appearanceRequestsCannotInjectProfilesRolesPinsOrAnActiveAdult() {
        val adult = Profile("parent", "Parent", pinSalt = "a".repeat(32), pinHash = "b".repeat(64))
        val child = Profile("child", "Child", child = true, allowedApps = listOf("org.example.video"))
        val before = LauncherSettings(profiles = listOf(adult, child), activeProfileId = adult.id)
        val current = before.copy(activeProfileId = child.id, profiles = listOf(
            adult.copy(pinSalt = "c".repeat(32), pinHash = "d".repeat(64)),
            child.copy(allowedApps = listOf("org.example.game")),
        ))
        val requested = before.copy(largeText = true, activeProfileId = adult.id,
            profiles = listOf(Profile(adult.id, "Unprotected"), child.copy(child = false)))
        assertEquals(current.copy(largeText = true), SettingsCodec.mergePresentation(current, before, requested))
    }

    @Test fun presentationMergeIncludesEveryExistingAppearanceOption() {
        val before = LauncherSettings()
        val requested = before.copy(largeText = true, compactTiles = false, showHomeAssistant = false,
            showSettings = false, showWallpaper = false, showWeather = false, clock24 = false, showContinue = false,
            navigationSound = NavigationSound.Off, weatherLocation = WeatherLocation(50.0, 1.0, "Example"),
            favorites = emptyList(), removedContinue = setOf("org.example.video:finished"),
            profileStartup = ProfileStartup.ResumeLast)
        assertEquals(requested, SettingsCodec.mergePresentation(before, before, requested))
    }

    @Test fun existingRecordsCannotDefaultMissingAuthorityToAnAdult() {
        val valid = SettingsCodec.write(LauncherSettings())
        for (invalid in listOf(
            JSONObject(),
            JSONObject(valid.toString()).apply { remove("profiles") },
            JSONObject(valid.toString()).put("profiles", JSONObject()),
            JSONObject(valid.toString()).put("profiles", org.json.JSONArray()),
            JSONObject(valid.toString()).apply { remove("activeProfileId") },
            JSONObject(valid.toString()).put("activeProfileId", ""),
            JSONObject(valid.toString()).put("activeProfileId", 123)
        )) {
            assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(invalid) }
        }
        // A deliberately imported empty setup can still use documented defaults after adult approval.
        assertEquals("home", SettingsCodec.read(JSONObject()).activeProfileId)
    }

    @Test fun strictStoredRoundTripRetainsASelectedChildAndAdultProtection() {
        val adult = ProfileSecurity.protect(Profile("adult", "Adult"), "0123")
        val stored = LauncherSettings(profiles = listOf(adult, Profile("child", "Child", child = true)), activeProfileId = "child")
        assertEquals(stored, SettingsCodec.readStored(SettingsCodec.write(stored)))
    }

    @Test fun storedProfilesRequireAnExplicitBooleanRole() {
        val adult = ProfileSecurity.protect(Profile("adult", "Adult"), "0123")
        val settings = LauncherSettings(profiles = listOf(adult, Profile("child", "Child", child = true)))
        for (index in settings.profiles.indices) {
            val missing = SettingsCodec.write(settings)
            missing.getJSONArray("profiles").getJSONObject(index).remove("child")
            assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(missing) }
            for (role in listOf(JSONObject.NULL, "true", "false", 0, JSONObject())) {
                val malformed = SettingsCodec.write(settings)
                malformed.getJSONArray("profiles").getJSONObject(index).put("child", role)
                assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(malformed) }
            }
        }
    }

    @Test fun malformedStoredProtectionCannotBecomeAnUnprotectedAdult() {
        val adult = ProfileSecurity.protect(Profile("adult", "Adult"), "0123")
        val settings = LauncherSettings(profiles = listOf(adult), activeProfileId = adult.id)
        for ((salt, hash) in listOf(
            "broken" to "broken", "" to "", 123 to 456,
            JSONObject.NULL to adult.pinHash, adult.pinSalt to JSONObject.NULL,
        )) {
            val malformed = SettingsCodec.write(settings)
            malformed.getJSONArray("profiles").getJSONObject(0).put("pinSalt", salt).put("pinHash", hash)
            assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(malformed) }
        }
    }

    @Test fun absentOrNullProtectionRemainsValidForAnUnprotectedAdult() {
        val settings = LauncherSettings()
        assertEquals(settings, SettingsCodec.readStored(SettingsCodec.write(settings)))
        val explicitNulls = SettingsCodec.write(settings)
        explicitNulls.getJSONArray("profiles").getJSONObject(0)
            .put("pinSalt", JSONObject.NULL).put("pinHash", JSONObject.NULL)
        assertEquals(settings, SettingsCodec.readStored(explicitNulls))
    }

    @Test fun approvedImportsKeepTheirDefaultRoleWithoutWeakeningStoredParsing() {
        val imported = SettingsCodec.write(LauncherSettings())
        imported.getJSONArray("profiles").getJSONObject(0).remove("child")
        assertFalse(SettingsCodec.read(imported).profiles.single().child)
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(imported) }
    }

    @Test fun profileOrdersRoundTripAndOlderRecordsNeedNoMigration() {
        val settings = LauncherSettings(profiles = listOf(Profile("home", "Home", appOrder = listOf("org.example.second", "org.example.first"))))
        assertEquals(settings, SettingsCodec.readStored(SettingsCodec.write(settings)))
        val older = SettingsCodec.write(settings)
        older.getJSONArray("profiles").getJSONObject(0).remove("appOrder")
        assertEquals(emptyList<String>(), SettingsCodec.readStored(older).profiles[0].appOrder)
    }

    @Test fun importedProfileOrdersAreBoundedAndCannotContainIntentPayloads() {
        val settings = LauncherSettings(profiles = listOf(Profile("home", "Home", appOrder =
            listOf("org.example.video", "intent://payload", "org.example.video") + (0..110).map { "org.example.app$it" })))
        val order = SettingsCodec.read(SettingsCodec.write(settings)).profiles[0].appOrder
        assertEquals("org.example.video", order.first())
        assertTrue(order.size <= 100)
        assertEquals(order.size, order.distinct().size)
        assertFalse("intent://payload" in order)
    }
}

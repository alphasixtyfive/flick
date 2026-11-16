package app.flick.settings

import app.flick.profiles.Profile
import app.flick.profiles.ProfileSecurity
import app.flick.weather.WeatherLocation
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

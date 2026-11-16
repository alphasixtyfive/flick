package app.flick.settings

import android.content.Context
import app.flick.profiles.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("flick_native", Context.MODE_PRIVATE)
    private val installation = context.getSharedPreferences("flick_installation", Context.MODE_PRIVATE)
    private val previouslyInitialized = installation.contains("initialized") ||
        preferences.all.isNotEmpty() || legacyConfigurationExists(context)
    private val mutex = Mutex()
    private val mutableRecovery = MutableStateFlow(false)
    val requiresRecovery = mutableRecovery.asStateFlow()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): LauncherSettings {
        return runCatching {
            val restored = restoreSettings(preferences.getString("settings", null), previouslyInitialized)
            check(!restored.requiresRecovery) { "Stored settings need recovery" }
            if (!preferences.contains("settings")) {
                check(preferences.edit().putString("settings", SettingsCodec.write(restored.settings).toString()).commit()) {
                    "Initial settings could not be saved"
                }
            }
            if (!installation.contains("initialized")) {
                check(installation.edit().putBoolean("initialized", true).commit()) { "Setup could not be recorded" }
            }
            restored.settings
        }.getOrElse {
            mutableRecovery.value = true
            recoverySettings()
        }
    }

    /** Earlier versions could save account or pairing data before ever writing launcher settings. */
    private fun legacyConfigurationExists(context: Context): Boolean = listOf(
        "flick_home_assistant" to "url", "flick_integration" to "device_id",
        "wallpapers" to "settings", "flick_profile_security" to "failures",
    ).any { (store, key) -> context.getSharedPreferences(store, Context.MODE_PRIVATE).contains(key) }

    suspend fun save(settings: LauncherSettings) = update { settings }

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) = mutex.withLock {
        val clean = SettingsCodec.read(SettingsCodec.write(transform(mutable.value)))
        if (clean == mutable.value && !mutableRecovery.value) return@withLock
        withContext(NonCancellable + Dispatchers.IO) {
            val previous = preferences.getString("settings", null)
            if (!preferences.edit().putString("settings", SettingsCodec.write(clean).toString()).commit()) {
                preferences.edit().putString("settings", previous).commit()
                error("Settings could not be saved")
            }
            mutable.value = clean
            mutableRecovery.value = false
        }
    }
}

internal data class RestoredSettings(val settings: LauncherSettings, val requiresRecovery: Boolean)

/** Only a genuinely new setup may start with an unprotected Home profile. */
internal fun restoreSettings(raw: String?, previouslyInitialized: Boolean): RestoredSettings = runCatching {
    if (raw == null) {
        check(!previouslyInitialized) { "Stored settings are missing" }
        RestoredSettings(LauncherSettings(), false)
    } else RestoredSettings(SettingsCodec.readStored(JSONObject(raw)), false)
}.getOrElse {
    RestoredSettings(recoverySettings(), true)
}

private fun recoverySettings() = LauncherSettings(
    profiles = listOf(Profile("recovery", "Setup required", child = true)), activeProfileId = "recovery",
)

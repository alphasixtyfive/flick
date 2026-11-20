package app.flick.tv.settings

import android.content.Context
import app.flick.tv.profiles.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsStore(context: Context, private val startupRecoveryFailed: Boolean = false) {
    private val preferences = context.getSharedPreferences("flick_native", Context.MODE_PRIVATE)
    private val installation = context.getSharedPreferences("flick_installation", Context.MODE_PRIVATE)
    private val previouslyInitialized = installation.contains("initialized") ||
        preferences.all.isNotEmpty() || existingPrivateConfigurationExists(context)
    private val mutex = Mutex()
    private val mutableRecovery = MutableStateFlow(false)
    val requiresRecovery = mutableRecovery.asStateFlow()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): LauncherSettings {
        return runCatching {
            check(!startupRecoveryFailed) { "Restore recovery could not finish" }
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

    /** Existing private data means missing launcher settings must enter recovery, never default adult setup. */
    private fun existingPrivateConfigurationExists(context: Context): Boolean = listOf(
        "flick_home_assistant" to "url", "flick_integration" to "device_id",
        "wallpapers" to "settings", "flick_profile_security" to "failures",
    ).any { (store, key) -> context.getSharedPreferences(store, Context.MODE_PRIVATE).contains(key) }

    suspend fun save(settings: LauncherSettings) = update { settings }

    /** Reload only after a local transaction rollback, before restoring profile authority. */
    internal suspend fun reloadAfterRollback() = mutex.withLock {
        withContext(Dispatchers.IO) { mutable.value = load() }
    }

    internal fun requireRecovery() {
        mutableRecovery.value = true
        mutable.value = recoverySettings()
    }

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) = mutex.withLock {
        val requested = transform(mutable.value)
        val (clean, encoded) = withContext(Dispatchers.Default) {
            val normalized = SettingsCodec.read(SettingsCodec.write(requested))
            normalized to SettingsCodec.write(normalized).toString()
        }
        if (clean == mutable.value && !mutableRecovery.value) return@withLock
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable + Dispatchers.IO) {
            val previous = preferences.getString("settings", null)
            if (!preferences.edit().putString("settings", encoded).commit()) {
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

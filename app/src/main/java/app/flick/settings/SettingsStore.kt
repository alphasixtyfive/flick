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
    private val mutex = Mutex()
    private val mutableRecovery = MutableStateFlow(false)
    val requiresRecovery = mutableRecovery.asStateFlow()
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): LauncherSettings {
        if (!preferences.contains("settings")) return LauncherSettings()
        return runCatching {
            val raw = preferences.getString("settings", null) ?: error("Stored settings are missing")
            SettingsCodec.readStored(JSONObject(raw))
        }.getOrElse {
            mutableRecovery.value = true
            LauncherSettings(profiles = listOf(Profile("recovery", "Setup required", child = true)), activeProfileId = "recovery")
        }
    }

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

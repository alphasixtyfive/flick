package app.flick.tv.platform

import android.content.SharedPreferences
import java.io.IOException

/** A failed disk commit has already changed Android's in-memory preferences. Restore those too. */
internal fun SharedPreferences.commitOrRestore(failureMessage: String, update: SharedPreferences.Editor.() -> Unit) {
    synchronized(this) {
        val previous = all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
        val changes = edit().apply(update)
        val saved = try { changes.commit() } catch (_: Exception) { false }
        if (saved) return

        try {
            val rollback = edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> rollback.putString(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        rollback.putStringSet(key, value as Set<String>)
                    }
                }
            }
            rollback.commit()
        } catch (_: Exception) {
            // The original write still failed; callers must not publish the candidate state.
        }
        throw IOException(failureMessage)
    }
}

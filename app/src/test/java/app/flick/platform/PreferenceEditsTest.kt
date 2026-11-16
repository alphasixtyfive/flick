package app.flick.platform

import android.content.SharedPreferences
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PreferenceEditsTest {
    @Test fun aFailedCommitRestoresEveryTypeAndRemovesNewKeys() {
        val initial = mapOf<String, Any>("text" to "original", "number" to 3, "long" to 4L,
            "float" to .5f, "flag" to true, "set" to setOf("one", "two"))
        val preferences = MemoryPreferences(initial)
        preferences.failNextCommit = true
        assertFails { preferences.commitOrRestore("Could not save") {
            clear(); putString("new", "candidate"); putBoolean("flag", false)
        } }
        assertEquals(initial, preferences.all)
    }

    @Test fun aSuccessfulCommitPublishesItsChangesOnce() {
        val preferences = MemoryPreferences(mapOf("old" to "value"))
        preferences.commitOrRestore("Could not save") { remove("old"); putBoolean("enabled", true) }
        assertEquals(mapOf("enabled" to true), preferences.all)
        assertEquals(1, preferences.commits)
    }

    @Test fun rollbackStillRestoresMemoryWhenTheDiskRemainsUnavailable() {
        val preferences = MemoryPreferences(mapOf("enabled" to false))
        preferences.failEveryCommit = true
        assertFails { preferences.commitOrRestore("Could not save") { putBoolean("enabled", true) } }
        assertFalse(preferences.getBoolean("enabled", true))
        assertEquals(2, preferences.commits)
    }

    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected a failed commit") } catch (_: IOException) { }
    }
}

/** Mirrors Android's ordering: memory changes before the disk-write result is returned. */
private class MemoryPreferences(initial: Map<String, Any>) : SharedPreferences {
    private val values = initial.toMutableMap()
    var failNextCommit = false
    var failEveryCommit = false
    var commits = 0
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val edits = mutableMapOf<String, Any?>()
        private var clearAll = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { edits[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (clearAll) values.clear()
            edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            commits++
            val success = !failNextCommit && !failEveryCommit
            failNextCommit = false
            return success
        }
    }
}

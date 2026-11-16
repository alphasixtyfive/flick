package app.flick.homeassistant

/** Optional physical-screen observation, independent of the controls a user can operate. */
enum class HaDisplayPower {
    Disabled, Unknown, On, Off,
}

internal object HaDisplayPowerPolicy {
    private val identifier = Regex("media_player\\.[a-z0-9_]{1,128}")
    fun entityId(value: String): String? = value.takeIf { identifier.matches(it) }
    fun selection(value: HaControlSpec?): HaControlSpec? = value?.let {
        if (entityId(it.entityId) == null) throw HaException("Choose a media player for the physical TV")
        it.copy(name = HaPolicy.text(it.name).ifEmpty { it.entityId.substringAfter('.').replace('_', ' ') })
    }
    fun state(value: String?): HaDisplayPower = when (value) {
        "off" -> HaDisplayPower.Off
        "on", "idle", "playing", "paused", "buffering" -> HaDisplayPower.On
        else -> HaDisplayPower.Unknown
    }
}

/** A reconnect needs a fresh snapshot. An older HTTP read cannot undo a newer event or outage. */
internal class HaDisplayPowerMonitor {
    var state = HaDisplayPower.Disabled
        private set
    var interruptions = 0L
        private set
    private var entityId: String? = null
    private var connected = false
    private var revision = 0L

    fun select(id: String?) {
        entityId = id
        connected = false
        revision++
        publish(if (id == null) HaDisplayPower.Disabled else HaDisplayPower.Unknown)
    }

    fun connection(available: Boolean) {
        connected = available
        revision++
        publish(if (entityId == null) HaDisplayPower.Disabled else HaDisplayPower.Unknown)
    }

    fun snapshotRevision(): Long? = revision.takeIf { connected && entityId != null }

    fun observe(id: String, value: String?, expectedRevision: Long? = null) {
        if (!connected || id != entityId || (expectedRevision != null && expectedRevision != revision)) return
        revision++
        publish(HaDisplayPowerPolicy.state(value))
    }

    private fun publish(value: HaDisplayPower) {
        if (value != state && value in setOf(HaDisplayPower.Unknown, HaDisplayPower.Off)) interruptions++
        state = value
    }
}

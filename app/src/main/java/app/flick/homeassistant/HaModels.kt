package app.flick.homeassistant

data class HaControlSpec(val entityId: String, val name: String)

data class HaEntity(val entityId: String, val name: String, val domain: String)

data class HaConfiguration(
    val url: String = "",
    val configured: Boolean = false,
    val controls: List<HaControlSpec> = emptyList(),
    val displayPowerEntity: HaControlSpec? = null,
)

internal fun HaConfiguration.observedEntityIds(): Set<String> =
    controls.mapTo(mutableSetOf()) { it.entityId }.apply { displayPowerEntity?.let { add(it.entityId) } }

/** State always comes from Home Assistant. Pending describes a request, never an invented value. */
data class HaControl(
    val id: String,
    val name: String,
    val kind: String,
    val state: String? = null,
    val brightness: Int? = null,
    val pending: Boolean = false,
    val error: String? = null
)

enum class HaConnectionState(val label: String) {
    Unknown("Checking…"),
    NotConfigured("Not connected"),
    Connecting("Connecting…"),
    Connected("Connected"),
    Offline("Offline"),
}

/** Either authenticated transport can keep controls available while the other reconnects. */
internal data class HaConnectionHealth(
    val httpAvailable: Boolean = false,
    val eventsAvailable: Boolean = false,
    val checking: Boolean = true,
    val authenticationRejected: Boolean = false,
) {
    val state: HaConnectionState get() = when {
        authenticationRejected -> HaConnectionState.Offline
        httpAvailable || eventsAvailable -> HaConnectionState.Connected
        checking -> HaConnectionState.Connecting
        else -> HaConnectionState.Offline
    }

    fun httpResult(available: Boolean, rejected: Boolean = false) = copy(
        httpAvailable = available,
        checking = false,
        authenticationRejected = if (available) false else authenticationRejected || rejected,
    )

    fun eventsResult(available: Boolean, rejected: Boolean = false) = copy(
        eventsAvailable = available,
        authenticationRejected = if (available) false else authenticationRejected || rejected,
    )
}

class HaException(message: String, val authenticationRejected: Boolean = false) : Exception(message)

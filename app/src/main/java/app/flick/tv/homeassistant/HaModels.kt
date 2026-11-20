package app.flick.tv.homeassistant

data class HaControlSpec(val entityId: String, val name: String)

data class HaEntity(val entityId: String, val name: String, val domain: String)

data class HaConfiguration(
    val url: String = "",
    val configured: Boolean = false,
    val controls: List<HaControlSpec> = emptyList(),
    val displayPowerEntity: HaControlSpec? = null,
) {
    internal val observedEntityIds: Set<String> =
        controls.mapTo(mutableSetOf()) { it.entityId }.apply { displayPowerEntity?.let { add(it.entityId) } }
}

/** Reported state stays separate from the target of an in-flight request. */
data class HaControl(
    val id: String,
    val name: String,
    val kind: String,
    val state: String? = null,
    val brightness: Int? = null,
    val pending: Boolean = false,
    val error: String? = null,
    val requestedOn: Boolean? = null,
)

internal fun HaControl.withRequest(pending: Boolean, targetOn: Boolean? = null, error: String? = null): HaControl =
    copy(pending = pending, requestedOn = targetOn.takeIf { pending && kind in HaPolicy.powerDomains }, error = error)

/** A pending switch shows the requested position while reported state remains authoritative. */
internal val HaControl.displayedOn: Boolean?
    get() = when {
        kind !in HaPolicy.powerDomains || (state != "on" && state != "off") -> null
        pending && requestedOn != null -> requestedOn
        else -> state == "on"
    }

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

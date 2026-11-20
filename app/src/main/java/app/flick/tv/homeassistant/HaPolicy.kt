package app.flick.tv.homeassistant

import java.net.URI

internal object HaPolicy {
    const val maxControls = 16
    private val domains = setOf("light", "switch", "input_boolean", "scene", "script", "sensor", "binary_sensor", "camera")
    val powerDomains = setOf("light", "switch", "input_boolean")
    val actionDomains = setOf("scene", "script")
    private val identifier = Regex("[a-z][a-z0-9_]{0,31}\\.[a-z0-9_]{1,128}")

    fun domain(entityId: String): String? = entityId.substringBefore('.').takeIf {
        identifier.matches(entityId) && it in domains
    }

    fun canonicalUrl(value: String): String {
        val uri = try { URI(value.trim()).normalize() } catch (_: Exception) {
            throw HaException("Enter a valid Home Assistant address")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 ||
            uri.toString().length > 2048 || uri.rawPath.orEmpty().contains('%')) {
            throw HaException("Use an http or https address without passwords, queries or fragments")
        }
        return uri.toASCIIString().trimEnd('/')
    }

    fun validToken(value: String): Boolean = Regex("[A-Za-z0-9._-]{20,4096}").matches(value)

    fun confirms(state: String?, on: Boolean): Boolean = state == if (on) "on" else "off"

    fun text(value: String, maximum: Int = 64): String = value.filterNot {
        it.isISOControl() || when (Character.getType(it)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
            else -> false
        }
    }.take(maximum).trim()

    fun controls(values: List<HaControlSpec>): List<HaControlSpec> {
        if (values.size > maxControls) throw HaException("Choose at most $maxControls controls")
        if (values.any { domain(it.entityId) == null }) throw HaException("This entity type is not supported")
        return values.distinctBy { it.entityId }.map {
            it.copy(name = text(it.name).ifEmpty { it.entityId.substringAfter('.').replace('_', ' ') })
        }
    }
}

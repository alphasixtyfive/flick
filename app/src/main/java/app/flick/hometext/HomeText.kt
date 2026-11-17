package app.flick.hometext

import org.json.JSONObject

enum class HomeTextStatus { Disabled, Waiting, Connected, Unsupported, Unavailable }

data class HomeTextProfile(val id: String, val child: Boolean, val sessionGeneration: Long)

/** Returning to a profile creates a fresh request generation as well as a fresh profile session. */
data class HomeTextContext(val profile: HomeTextProfile, val generation: Long)

data class HomeTextSnapshot(
    val profileId: String,
    val generation: Long,
    val revision: Long,
    val text: String,
    val showForChildren: Boolean,
) {
    override fun toString() = "HomeTextSnapshot(privateData=redacted)"
}

data class HomeTextState(
    val context: HomeTextContext? = null,
    val status: HomeTextStatus = HomeTextStatus.Disabled,
    val snapshot: HomeTextSnapshot? = null,
) {
    fun visibleFor(profileId: String, child: Boolean, sessionGeneration: Long): HomeTextSnapshot? {
        val expected = context ?: return null
        val value = snapshot ?: return null
        return value.takeIf {
            status == HomeTextStatus.Connected && expected.profile == HomeTextProfile(profileId, child, sessionGeneration) &&
                it.profileId == profileId && it.generation == expected.generation &&
                it.text.isNotBlank() && (!child || it.showForChildren)
        }
    }

    override fun toString() = "HomeTextState(status=$status, privateData=redacted)"
}

/** One bounded, literal message on the authenticated socket. */
internal object HomeTextProtocol {
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private val profileId = Regex("[A-Za-z0-9_-]{1,64}")
    private val fields = setOf("profile_id", "profile_generation", "revision", "text", "show_for_children")

    fun read(value: JSONObject): HomeTextSnapshot {
        require(value.keys().asSequence().toSet() == fields) { "Invalid Home screen text fields" }
        val id = value.opt("profile_id") as? String ?: error("Invalid Home screen text profile")
        require(profileId.matches(id)) { "Invalid Home screen text profile" }
        val children = value.opt("show_for_children") as? Boolean ?: error("Invalid Home screen text visibility")
        val message = text(value.opt("text"))
        return HomeTextSnapshot(id, integer(value, "profile_generation"), integer(value, "revision"), message, children)
    }

    private fun text(value: Any?): String {
        val text = value as? String ?: error("Invalid Home screen message")
        require(text.length <= 240 && text.codePointCount(0, text.length) <= 120) { "Invalid Home screen message" }
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            val point = if (Character.isHighSurrogate(character)) {
                require(index < text.length && Character.isLowSurrogate(text[index])) { "Invalid Home screen message" }
                Character.toCodePoint(character, text[index++])
            } else {
                require(!Character.isLowSurrogate(character)) { "Invalid Home screen message" }
                character.code
            }
            require(!Character.isISOControl(point) && point !in 0x2028..0x2029 &&
                point != 0x061C && point !in 0x200E..0x200F && point !in 0x202A..0x202E && point !in 0x2066..0x206F) {
                "Invalid Home screen message"
            }
        }
        return text
    }

    private fun integer(value: JSONObject, key: String): Long {
        val number = value.opt(key) as? Number ?: error("Invalid Home screen text number")
        val decimal = number.toDouble()
        require(decimal.isFinite() && decimal == number.toLong().toDouble() && number.toLong() in 1..MAX_INTEGER) {
            "Invalid Home screen text number"
        }
        return number.toLong()
    }
}

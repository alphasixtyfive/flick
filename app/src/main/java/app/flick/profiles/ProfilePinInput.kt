package app.flick.profiles

/** Ephemeral input: never saved, and diagnostics cannot expose its digits. */
internal class ProfilePinInput private constructor(private val digits: String) {
    constructor() : this("")

    val length: Int get() = digits.length
    val complete: Boolean get() = length == 4

    fun digit(value: Char): ProfilePinInput {
        require(value in '0'..'9')
        return if (length < 4) ProfilePinInput(digits + value) else this
    }

    fun delete(): ProfilePinInput = if (digits.isEmpty()) this else ProfilePinInput(digits.dropLast(1))
    fun forVerification(): String = digits
    override fun toString() = "ProfilePinInput(redacted)"
}

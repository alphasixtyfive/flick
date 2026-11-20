package app.flick.tv.profiles

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ProfileSecurity(context: Context) {
    private val preferences = context.getSharedPreferences("flick_profile_security", Context.MODE_PRIVATE)

    fun verify(profile: Profile, pin: String): Boolean {
        if (profile.child || profile.pinHash == null) return true
        val now = System.currentTimeMillis()
        check(preferences.getLong("lockedUntil", 0) <= now) { "Please wait before trying the PIN again" }
        val accepted = matchesPin(profile, pin)
        val failures = if (accepted) 0 else preferences.getInt("failures", 0) + 1
        check(preferences.edit().putInt("failures", if (failures >= 5) 0 else failures)
            .putLong("lockedUntil", if (failures >= 5) now + 60_000 else 0).commit()) { "PIN checks could not be saved" }
        return accepted
    }

    companion object {
        fun protect(profile: Profile, pin: String): Profile {
            require(pin.matches(Regex("[0-9]{4}"))) { "Use a PIN with exactly four digits" }
            val salt = ByteArray(16).also(SecureRandom()::nextBytes).hex()
            return profile.copy(pinSalt = salt, pinHash = derive(pin, salt).hex())
        }
        internal fun matchesPin(profile: Profile, pin: String): Boolean {
            if (!pin.matches(Regex("[0-9]{4}")) || profile.pinSalt == null || profile.pinHash == null) return false
            return MessageDigest.isEqual(derive(pin, profile.pinSalt), unhex(profile.pinHash))
        }
        private fun derive(pin: String, salt: String): ByteArray {
            val specification = PBEKeySpec(pin.toCharArray(), unhex(salt), 100_000, 256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
            finally { specification.clearPassword() }
        }
        private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

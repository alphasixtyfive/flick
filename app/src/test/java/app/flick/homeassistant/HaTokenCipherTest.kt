package app.flick.homeassistant

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class HaTokenCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun tokenRoundTripsWithoutBeingStoredAsPlaintext() {
        val key = key()
        val token = "synthetic-token-for-test-only"
        val sealed = HaTokenCipher.encrypt(key, "https://example.test", token)
        assertEquals(token, HaTokenCipher.decrypt(key, "https://example.test", sealed))
        assertFalse(String(sealed.bytes).contains(token))
        assertEquals(12, sealed.iv.size)
    }

    @Test fun eachSaveUsesAFreshNonce() {
        val key = key()
        val first = HaTokenCipher.encrypt(key, "https://example.test", "synthetic-test")
        val second = HaTokenCipher.encrypt(key, "https://example.test", "synthetic-test")
        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.bytes.contentEquals(second.bytes))
    }

    @Test fun tamperingOrMovingCredentialsToAnotherOriginFailsAuthentication() {
        val key = key()
        val sealed = HaTokenCipher.encrypt(key, "https://example.test", "synthetic-test")
        assertTrue(runCatching { HaTokenCipher.decrypt(key, "https://other.test", sealed) }.isFailure)
        val changed = sealed.bytes.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { HaTokenCipher.decrypt(key, "https://example.test", HaTokenCipher.Sealed(sealed.iv, changed)) }.isFailure)
    }
}

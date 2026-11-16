package app.flick.profiles

import org.junit.Assert.*
import org.junit.Test

class ProfileSecurityTest {
    @Test fun onlyTheEntireFourDigitPinMatchesTheStoredHash() {
        val protected = ProfileSecurity.protect(Profile("viewer", "Viewer"), "0123")
        assertTrue(ProfileSecurity.matchesPin(protected, "0123"))
        for (candidate in listOf("01234", "012345", "012", "", "0x23", "9123")) {
            assertFalse(ProfileSecurity.matchesPin(protected, candidate))
        }
    }

    @Test fun invalidLengthIsRejectedBeforeAnyStoredHashIsParsed() {
        val malformed = Profile("viewer", "Viewer", pinSalt = "not hex", pinHash = "not hex")
        assertFalse(ProfileSecurity.matchesPin(malformed, "12345"))
        assertFalse(ProfileSecurity.matchesPin(malformed, "123"))
    }

    @Test fun protectionRejectsEveryNonFourDigitInput() {
        for (candidate in listOf("", "123", "12345", "abcd", "12 3")) {
            try {
                ProfileSecurity.protect(Profile("viewer", "Viewer"), candidate)
                fail("Invalid PIN was accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

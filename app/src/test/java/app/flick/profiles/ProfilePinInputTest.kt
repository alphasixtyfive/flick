package app.flick.profiles

import org.junit.Assert.*
import org.junit.Test

class ProfilePinInputTest {
    private fun input(digits: String) = digits.fold(ProfilePinInput()) { current, digit -> current.digit(digit) }

    @Test fun aPinIsReadyAtExactlyFourDigits() {
        assertFalse(input("123").complete)
        assertTrue(input("1234").complete)
    }

    @Test fun leadingZeroesArePreservedWithoutDiagnosticDisclosure() {
        val value = input("0012")
        assertEquals("0012", value.forVerification())
        assertEquals(4, value.length)
        assertFalse(value.toString().contains("0012"))
    }

    @Test fun extraDigitsCannotExtendTheSecurityLimit() {
        val value = input("1234")
        assertSame(value, value.digit('5'))
        assertEquals(4, value.length)
        assertEquals("1234", value.forVerification())
    }

    @Test fun deleteOnlyRemovesTheLastDigitAndAnEmptyEntryIsSafe() {
        assertEquals("012", input("0123").delete().forVerification())
        assertEquals("", ProfilePinInput().delete().forVerification())
        assertFalse(input("1234").delete().complete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonNumericKeyCannotBecomePinInput() {
        ProfilePinInput().digit('x')
    }
}

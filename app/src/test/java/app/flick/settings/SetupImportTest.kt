package app.flick.settings

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SetupImportTest {
    @Test fun normalSetupAndQuotedBracesAreAccepted() {
        val setup = SetupImport.parse("""{"settings":{"profiles":[{"id":"home","name":"A [quiet] {view}"}]},"wallpaper":{"photos":[{"uri":"https://example.org/image.jpg"}]}}""")
        assertEquals("A [quiet] {view}", setup.settings.profiles.first().name)
        assertEquals("https://example.org/image.jpg", setup.wallpaper?.photos?.first()?.uri)
    }

    @Test fun excessiveDepthIsRejectedBeforeParsing() {
        val nested = "[".repeat(17) + "0" + "]".repeat(17)
        assertThrows(IllegalArgumentException::class.java) { SetupImport.parse(nested) }
    }

    @Test fun oversizedStreamsStopAtTheBound() {
        val input = ByteArrayInputStream(ByteArray(512 * 1024 + 1))
        assertThrows(IllegalArgumentException::class.java) { SetupImport.read(input) }
    }

    @Test fun importedCredentialsAreRedactedFromDiagnostics() {
        val credentials = ImportedHomeAssistant("https://example.org", "private-example", emptyList())
        assertFalse(credentials.toString().contains("private-example"))
    }
}

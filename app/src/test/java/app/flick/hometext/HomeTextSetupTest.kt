package app.flick.hometext

import app.flick.homeassistant.HaException
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class HomeTextSetupTest {
    @Test fun setupLinkPreservesTheConfiguredServerAndProxyPath() {
        assertEquals("https://ha.example.test/proxy/config/integrations/integration/flick",
            HomeTextSetup.integrationUrl(" https://ha.example.test/proxy/ "))
    }

    @Test fun setupLinkRejectsCredentialsQueriesAndInvalidAddresses() {
        listOf("", "file:///settings", "https://user:secret@ha.example.test",
            "https://ha.example.test?token=value", "https://ha.example.test#fragment").forEach {
            assertThrows(HaException::class.java) { HomeTextSetup.integrationUrl(it) }
        }
    }

    @Test fun displayedQrCodeDecodesToTheSetupLink() {
        val url = HomeTextSetup.integrationUrl("https://ha.example.test:8123")
        val size = HomeTextSetup.QR_SIZE
        val pixels = HomeTextSetup.qrPixels(url)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels))))
        assertEquals(url, decoded.text)
        assertEquals(0xFFFFFFFF.toInt(), pixels.first())
    }
}

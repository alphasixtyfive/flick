package app.flick.hometext

import app.flick.homeassistant.HaPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

internal object HomeTextSetup {
    const val QR_SIZE = 320
    fun integrationUrl(address: String): String =
        HaPolicy.canonicalUrl(address) + "/config/integrations/integration/flick"

    /** Generated locally; the setup link contains no token or pairing secret. */
    fun qrPixels(url: String): IntArray {
        require(url.length <= 2200)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        return IntArray(QR_SIZE * QR_SIZE) { index ->
            if (matrix[index % QR_SIZE, index / QR_SIZE]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
}

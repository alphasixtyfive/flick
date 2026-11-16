package app.flick.homeassistant

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encryption format is testable separately from Android's non-exportable key provider. */
internal object HaTokenCipher {
    data class Sealed(val iv: ByteArray, val bytes: ByteArray)

    fun encrypt(key: SecretKey, server: String, token: String): Sealed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))
        return Sealed(cipher.iv, cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(key: SecretKey, server: String, value: Sealed): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.iv))
        cipher.updateAAD(server.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(value.bytes), Charsets.UTF_8)
    }
}

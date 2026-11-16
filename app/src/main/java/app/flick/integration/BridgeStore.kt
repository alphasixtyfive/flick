package app.flick.integration

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.flick.platform.commitOrRestore
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

/** Pairing secrets never leave private storage except on the authenticated, origin-bound socket. */
internal class BridgeStore(context: Context) {
    private val preferences = context.getSharedPreferences("flick_integration", Context.MODE_PRIVATE)
    private var history: BridgeRequestHistory? = null

    fun state(): BridgeState {
        var id = preferences.getString("device_id", null)
        if (id == null || !id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) {
            id = UUID.randomUUID().toString()
            preferences.commitOrRestore("Integration settings could not be saved") { putString("device_id", id) }
        }
        return BridgeState(deviceId = id, enabled = preferences.getBoolean("enabled", false),
            notificationsEnabled = preferences.getBoolean("notifications", true),
            allowManagement = preferences.getBoolean("management", false))
    }

    fun setEnabled(value: Boolean) = saveFlag("enabled", value)
    fun setNotificationsEnabled(value: Boolean) = saveFlag("notifications", value)
    fun setAllowManagement(value: Boolean) = saveFlag("management", value)
    private fun saveFlag(name: String, value: Boolean) {
        preferences.commitOrRestore("Integration settings could not be saved") { putBoolean(name, value) }
    }

    fun identity(origin: String): BridgeIdentity? {
        if (preferences.getString("origin", null) != origin) return null
        return runCatching {
            val iv = Base64.decode(preferences.getString("iv", null) ?: return null, Base64.NO_WRAP)
            val bytes = Base64.decode(preferences.getString("secret", null) ?: return null, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
            val secret = String(cipher.doFinal(bytes), Charsets.UTF_8)
            if (!secret.matches(Regex("[a-f0-9]{64}"))) return null
            BridgeIdentity(origin, secret, preferences.getBoolean("paired", false))
        }.getOrNull()
    }

    fun beginPairing(origin: String): BridgeIdentity {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(origin.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        preferences.commitOrRestore("Pairing could not be saved") {
            putString("origin", origin); putBoolean("paired", false); putBoolean("management", false)
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)); remove("requests")
        }
        history = BridgeRequestHistory()
        return BridgeIdentity(origin, secret, false)
    }

    fun markPaired(origin: String) {
        check(preferences.getString("origin", null) == origin) { "Home Assistant changed" }
        preferences.commitOrRestore("Pairing could not be saved") { putBoolean("paired", true) }
    }

    fun claim(requestId: String): Boolean {
        val requests = history ?: run {
            val raw = preferences.getString("requests", "[]").orEmpty()
            val values = runCatching {
                require(raw.length <= 24 * 1024)
                val array = JSONArray(raw)
                (0 until minOf(array.length(), 256)).mapNotNull { array.opt(it) as? String }.filter(BridgeProtocol::validId)
            }.getOrDefault(emptyList())
            BridgeRequestHistory(values).also { history = it }
        }
        if (!requests.claim(requestId)) return false
        preferences.commitOrRestore("Request could not be recorded") { putString("requests", JSONArray(requests.snapshot()).toString()) }
        return true
    }

    fun forget() {
        preferences.commitOrRestore("Pairing could not be removed") {
            remove("origin"); remove("paired"); remove("iv"); remove("secret"); remove("requests")
            putBoolean("enabled", false); putBoolean("management", false)
        }
        history = null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "flick.integration.secret.v1" }
}

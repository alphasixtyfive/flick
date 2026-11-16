package app.flick.homeassistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.flick.platform.commitOrRestore
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

internal class HaCredentials(val url: String, val token: String) {
    override fun toString() = "HaCredentials(redacted)"
}

/** The token is encrypted with a non-exportable app key and bound to its exact server address. */
internal class HaCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("flick_home_assistant", Context.MODE_PRIVATE)

    fun url(): String = preferences.getString("url", "").orEmpty()

    fun credentials(): HaCredentials? {
        val url = url().takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val iv = Base64.decode(preferences.getString("token_iv", null) ?: return null, Base64.NO_WRAP)
            val encrypted = Base64.decode(preferences.getString("token_ciphertext", null) ?: return null, Base64.NO_WRAP)
            HaCredentials(url, HaTokenCipher.decrypt(key(), url, HaTokenCipher.Sealed(iv, encrypted)))
                .takeIf { HaPolicy.validToken(it.token) }
        }.getOrNull()
    }

    fun controls(): List<HaControlSpec> = runCatching {
        val array = JSONArray(preferences.getString("controls", "[]"))
        HaPolicy.controls((0 until minOf(array.length(), HaPolicy.maxControls)).map { index ->
            val value = array.getJSONObject(index)
            HaControlSpec(value.getString("entityId"), value.optString("name"))
        })
    }.getOrDefault(emptyList())

    fun save(credentials: HaCredentials, controls: List<HaControlSpec>) {
        val encrypted = HaTokenCipher.encrypt(key(), credentials.url, credentials.token)
        preferences.commitOrRestore("Home Assistant sign-in could not be saved") {
            putString("url", credentials.url)
            putString("token_iv", Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
            putString("token_ciphertext", Base64.encodeToString(encrypted.bytes, Base64.NO_WRAP))
            putString("controls", encodeControls(controls))
        }
    }

    fun saveControls(values: List<HaControlSpec>) {
        preferences.commitOrRestore("The controls could not be saved") { putString("controls", encodeControls(values)) }
    }

    fun clear() { preferences.commitOrRestore("Home Assistant sign-in could not be removed") { clear() } }

    private fun encodeControls(values: List<HaControlSpec>) = JSONArray(values.map {
        JSONObject().put("entityId", it.entityId).put("name", it.name)
    }).toString()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "flick.homeassistant.token.v1" }
}

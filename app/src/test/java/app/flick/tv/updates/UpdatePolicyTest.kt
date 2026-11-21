package app.flick.tv.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val repository = "example/flick"
    private val digest = "a".repeat(64)

    private fun release(): JSONObject = JSONObject().put("tag_name", "v0.2.19").put("draft", false)
        .put("prerelease", false).put("body", "Faster wallpaper transitions.")
        .put("assets", JSONArray().put(JSONObject().put("name", "Flick-TV-0.2.19.apk").put("state", "uploaded")
            .put("size", 4096).put("digest", "sha256:$digest")
            .put("browser_download_url", "https://github.com/$repository/releases/download/v0.2.19/Flick-TV-0.2.19.apk")))

    private fun asset(json: JSONObject) = json.getJSONArray("assets").getJSONObject(0)

    private fun rejected(json: JSONObject) {
        try { UpdatePolicy.release(json, setOf(repository)); fail("Untrusted release was accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: org.json.JSONException) { }
    }

    @Test fun acceptsOneCompleteStableUniversalAsset() {
        val value = UpdatePolicy.release(release(), setOf(repository))
        assertEquals("0.2.19", value.version)
        assertEquals(digest, value.sha256)
        assertEquals(4096L, value.size)
    }

    @Test fun renameSupportsBothExactRepositoryNames() {
        val former = repository
        val current = "example/flick-tv"
        val json = release()
        val allowed = setOf(former, current)
        assertEquals("0.2.19", UpdatePolicy.release(json, allowed).version)
        asset(json).put("browser_download_url", "https://github.com/$current/releases/download/v0.2.19/Flick-TV-0.2.19.apk")
        assertEquals("0.2.19", UpdatePolicy.release(json, allowed).version)
        assertTrue(UpdatePolicy.trustedMetadataUrl(UpdatePolicy.metadataUrl(123L)))
        assertFalse(UpdatePolicy.trustedMetadataUrl("https://api.github.com.evil.test/repositories/123/releases/latest"))
    }

    @Test fun excludesDraftsAndPrereleasesEvenWhenReturnedByLatest() {
        rejected(release().put("draft", true))
        rejected(release().put("prerelease", true))
        rejected(release().put("prerelease", "false"))
        rejected(release().apply { remove("draft") })
    }

    @Test fun rejectsMissingOrAmbiguousUniversalAssets() {
        rejected(release().apply { asset(this).put("name", "Flick-TV-0.2.19-arm64.apk") })
        rejected(release().apply { getJSONArray("assets").put(JSONObject(asset(this).toString())) })
        rejected(release().put("assets", JSONArray()))
        rejected(release().apply { asset(this).put("state", "new") })
    }

    @Test fun requiresDigestAndBoundedExactIntegerSize() {
        rejected(release().apply { asset(this).remove("digest") })
        rejected(release().apply { asset(this).put("digest", "sha256:123") })
        rejected(release().apply { asset(this).put("size", 0) })
        rejected(release().apply { asset(this).put("size", UpdatePolicy.MAX_APK_BYTES + 1) })
        rejected(release().apply { asset(this).put("size", 12.5) })
        rejected(release().apply { asset(this).put("size", "4096") })
    }

    @Test fun assetMustBelongToThisExactRepositoryTagAndFilename() {
        for (url in listOf(
            "http://github.com/$repository/releases/download/v0.2.19/Flick-TV-0.2.19.apk",
            "https://github.com/other/flick/releases/download/v0.2.19/Flick-TV-0.2.19.apk",
            "https://github.com/$repository/releases/download/v0.2.18/Flick-TV-0.2.19.apk",
            "https://github.com/$repository/releases/download/v0.2.19/Flick-TV-0.2.19.apk?download=1",
            "https://github.com.evil.test/$repository/releases/download/v0.2.19/Flick-TV-0.2.19.apk",
        )) rejected(release().apply { asset(this).put("browser_download_url", url) })
    }

    @Test fun onlyConventionalStableVersionTagsAreAccepted() {
        for (tag in listOf("v0.2.19-beta1", "v0.2", "v00.2.19", "v0.2.9999999999", "../../file", "v0.2.19+metadata"))
            rejected(release().put("tag_name", tag))
        assertTrue(UpdatePolicy.newer("0.10.0", "0.9.9"))
        assertFalse(UpdatePolicy.newer("0.2.18", "0.2.18"))
        assertFalse(UpdatePolicy.newer("0.2.17", "0.2.18"))
        assertFalse(UpdatePolicy.newer("0.2.19-beta", "0.2.18"))
    }

    @Test fun downloadRedirectsStayOnKnownHttpsHosts() {
        assertTrue(UpdatePolicy.trustedDownloadUrl("https://release-assets.githubusercontent.com/asset?token=example"))
        assertTrue(UpdatePolicy.trustedDownloadUrl("https://objects.githubusercontent.com/asset"))
        for (url in listOf("http://github.com/file", "https://github.com.evil.test/file", "https://evilgithubusercontent.com/file",
            "https://raw.githubusercontent.com/file", "https://github.com:8443/file", "https://user@github.com/file",
            "https://github.com/file#fragment", "file:///tmp/update.apk", "not a url"))
            assertFalse(url, UpdatePolicy.trustedDownloadUrl(url))
    }

    @Test fun acceptsSameSignerAndForwardRotationOnly() {
        assertTrue(UpdatePolicy.acceptsSigners(setOf("old"), setOf("old"), emptySet(), false, false))
        assertTrue(UpdatePolicy.acceptsSigners(setOf("old"), setOf("new"), setOf("old", "new"), false, false))
        assertFalse(UpdatePolicy.acceptsSigners(setOf("new"), setOf("old"), setOf("old"), false, false))
        assertFalse(UpdatePolicy.acceptsSigners(setOf("old"), setOf("other"), setOf("other"), false, false))
        assertFalse(UpdatePolicy.acceptsSigners(emptySet(), setOf("old"), setOf("old"), false, false))
    }

    @Test fun multipleSignersRequireTheExactCurrentSet() {
        assertTrue(UpdatePolicy.acceptsSigners(setOf("a", "b"), setOf("b", "a"), emptySet(), true, true))
        assertFalse(UpdatePolicy.acceptsSigners(setOf("a", "b"), setOf("a", "c"), emptySet(), true, true))
        assertFalse(UpdatePolicy.acceptsSigners(setOf("a", "b"), setOf("a"), setOf("a", "b"), true, false))
    }
}

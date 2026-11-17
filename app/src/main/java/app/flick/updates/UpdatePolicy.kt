package app.flick.updates

import java.net.URI
import org.json.JSONObject

data class UpdateRelease internal constructor(
    val version: String,
    val tag: String,
    val assetUrl: String,
    val sha256: String,
    val size: Long,
)

internal object UpdatePolicy {
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    const val MAX_METADATA_BYTES = 256 * 1024
    private val versionPattern = Regex("(?:v)?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")
    private val repositoryPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val digestPattern = Regex("sha256:([0-9a-fA-F]{64})")
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com")

    fun versionParts(value: String): List<Int>? = versionPattern.matchEntire(value)?.groupValues?.drop(1)?.map(String::toInt)

    fun newer(candidate: String, installed: String): Boolean {
        val left = versionParts(candidate) ?: return false
        val right = versionParts(installed) ?: return false
        return left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
    }

    fun metadataUrl(repository: String): String {
        require(repositoryPattern.matches(repository))
        return "https://api.github.com/repos/$repository/releases/latest"
    }

    fun release(json: JSONObject, repository: String): UpdateRelease {
        require(repositoryPattern.matches(repository))
        require(json.opt("draft") == false && json.opt("prerelease") == false)
        val tag = json.getString("tag_name")
        require(versionParts(tag) != null)
        val version = tag.removePrefix("v")
        val expectedName = "Flick-$version.apk"
        val assets = json.getJSONArray("assets")
        val matches = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name") == expectedName }
        require(matches.size == 1)
        val asset = matches.single()
        require(asset.optString("state") == "uploaded")
        val rawSize = asset.opt("size")
        require(rawSize is Number && rawSize.toDouble() == rawSize.toLong().toDouble())
        val size = rawSize.toLong()
        require(size in 1..MAX_APK_BYTES)
        val digest = digestPattern.matchEntire(asset.getString("digest"))?.groupValues?.get(1)?.lowercase()
        require(digest != null)
        val url = asset.getString("browser_download_url")
        require(url == "https://github.com/$repository/releases/download/$tag/$expectedName")
        return UpdateRelease(version, tag, url, digest, size)
    }

    fun trustedDownloadUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host in downloadHosts && uri.userInfo == null &&
            uri.fragment == null && (uri.port == -1 || uri.port == 443)
    } catch (_: Exception) { false }

    fun acceptsSigners(installed: Set<String>, candidate: Set<String>, history: Set<String>,
        installedMultiple: Boolean, candidateMultiple: Boolean): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false
        if (installedMultiple || candidateMultiple) return installedMultiple && candidateMultiple && installed == candidate
        if (installed.size != 1 || candidate.size != 1) return false
        return installed == candidate || (installed.single() in history && candidate.single() in history)
    }
}

internal class UpdateException(message: String) : Exception(message)

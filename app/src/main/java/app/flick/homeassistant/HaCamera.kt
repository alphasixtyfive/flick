package app.flick.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

internal data class HaCameraSnapshot(val entityId: String, val url: String)

internal object HaCamera {
    const val ViewAction = "flick_view_camera"
    private val streamPath = Regex("/api/hls/[A-Za-z0-9_-]{12,128}/[A-Za-z0-9_-]+\\.m3u8")

    fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port &&
            second.username.isEmpty() && second.password.isEmpty()

    fun snapshot(origin: String, image: String?): HaCameraSnapshot? {
        val server = origin.toHttpUrlOrNull() ?: return null
        val source = image ?: return null
        val url = server.resolve(source) ?: return null
        if (!sameOrigin(server, url) || url.fragment != null || url.queryParameter("authSig").isNullOrBlank()) return null
        val prefix = "/api/camera_proxy/"
        val basePath = server.encodedPath.trimEnd('/')
        val apiPath = if (url.encodedPath.startsWith(basePath + prefix)) url.encodedPath.removePrefix(basePath)
            else url.encodedPath
        if (!apiPath.startsWith(prefix)) return null
        val entityId = apiPath.removePrefix(prefix).takeIf(::isEntity) ?: return null
        val snapshotUrl = if (source.startsWith(prefix)) url.newBuilder().encodedPath(basePath + apiPath).build() else url
        return HaCameraSnapshot(entityId, snapshotUrl.toString())
    }

    fun streamUrl(origin: String, path: String): String? {
        val server = origin.toHttpUrlOrNull() ?: return null
        if (!streamPath.matches(path)) return null
        return server.newBuilder().encodedPath(server.encodedPath.trimEnd('/') + path).build().toString()
    }

    fun isEntity(value: String): Boolean = HaPolicy.domain(value) == "camera"
}

/** Use the same Home Assistant camera command as its frontend, with the TV's existing sign-in. */
internal suspend fun requestCameraStream(client: OkHttpClient, credentials: HaCredentials, camera: String): String {
    require(HaCamera.isEntity(camera))
    val result = CompletableDeferred<String>()
    val request = Request.Builder().url("${credentials.url}/api/websocket").build()
    val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 16_384) {
                result.completeExceptionally(HaException("Home Assistant returned an invalid camera response"))
                return
            }
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "auth_required" -> webSocket.send(JSONObject().put("type", "auth")
                    .put("access_token", credentials.token).toString())
                "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "camera/stream")
                    .put("entity_id", camera).put("format", "hls").toString())
                "auth_invalid" -> result.completeExceptionally(HaException("Home Assistant rejected sign-in"))
                "result" -> if (message.optInt("id") == 1) {
                    val path = message.optJSONObject("result")?.optString("url").orEmpty()
                    val stream = path.takeIf { message.optBoolean("success") }?.let { HaCamera.streamUrl(credentials.url, it) }
                    if (stream == null) result.completeExceptionally(HaException("Camera stream unavailable"))
                    else result.complete(stream)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            result.completeExceptionally(HaException("Home Assistant is unavailable"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            result.completeExceptionally(HaException("Home Assistant closed the camera request"))
        }
    })
    return try {
        withTimeoutOrNull(8_000) { result.await() } ?: throw HaException("Camera request timed out")
    } finally {
        socket.cancel()
    }
}

package app.flick.homeassistant

import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** HA's native event protocol. Only explicitly selected entities leave this adapter. */
internal class HaEventStream(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val credentials: HaCredentials,
    private val selected: () -> Set<String>,
    private val observed: (String, JSONObject?) -> Unit,
    private val ready: suspend () -> Unit,
    private val unavailable: (Boolean) -> Unit
) {
    private val socket = AtomicReference<WebSocket?>()
    private val currentAttempt = AtomicReference<HaEventAttempt?>()
    @Volatile private var closed = false
    @Volatile var connected = false
        private set

    suspend fun run() {
        var backoff = 500L
        while (scope.isActive && !closed) {
            val attempt = HaEventAttempt()
            currentAttempt.set(attempt)
            if (closed) { attempt.close(); return }
            val authenticated = attempt.authenticated
            val ended = attempt.ended
            var refresh: Job? = null
            var rejected = false
            val address = credentials.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    attempt.accept {
                        if (!scope.isActive) return@accept
                        if (text.length > 4 * 1024 * 1024) { webSocket.cancel(); ended.complete(Unit); return@accept }
                        val message = runCatching { JSONObject(text) }.getOrNull() ?: return@accept
                        when (message.optString("type")) {
                            "auth_required" -> webSocket.send(JSONObject().put("type", "auth")
                                .put("access_token", credentials.token).toString())
                            "auth_ok" -> webSocket.send(JSONObject().put("id", 1).put("type", "subscribe_events")
                                .put("event_type", "state_changed").toString())
                            "auth_invalid" -> {
                                rejected = true
                                authenticated.completeExceptionally(HaException("Home Assistant rejected sign-in"))
                                ended.complete(Unit)
                            }
                            "result" -> if (message.optInt("id") == 1) {
                                if (message.optBoolean("success")) authenticated.complete(Unit)
                                else authenticated.completeExceptionally(HaException("Home Assistant events are unavailable"))
                            }
                            "event" -> {
                                val event = message.optJSONObject("event") ?: return@accept
                                if (event.optString("event_type") != "state_changed") return@accept
                                val data = event.optJSONObject("data") ?: return@accept
                                val id = data.optString("entity_id")
                                if (id in selected()) observed(id, data.optJSONObject("new_state"))
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    attempt.accept {
                        ended.complete(Unit)
                        if (!authenticated.isCompleted) authenticated.completeExceptionally(HaException("Home Assistant events are unavailable"))
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { attempt.accept { ended.complete(Unit) } }
            }
            try {
                socket.set(client.newWebSocket(Request.Builder().url("$address/api/websocket").build(), listener))
                withTimeout(10_000) { authenticated.await() }
                var startRefresh = false
                attempt.accept {
                    if (!scope.isActive || ended.isCompleted) return@accept
                    connected = true
                    backoff = 500
                    startRefresh = true
                }
                if (startRefresh) refresh = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    if (attempt.isActive()) ready()
                }
                ended.await()
            } catch (cancelled: CancellationException) {
                if (!scope.isActive) throw cancelled
            } catch (_: Exception) {
                // Public errors are deliberately independent of tokens, headers and server responses.
            } finally {
                attempt.close()
                currentAttempt.compareAndSet(attempt, null)
                refresh?.cancel()
                connected = false
                socket.getAndSet(null)?.cancel()
            }
            if (closed) return
            unavailable(rejected)
            if (rejected) return
            delay(backoff + Random.nextLong(250))
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    fun close() {
        closed = true
        currentAttempt.getAndSet(null)?.close()
        connected = false
        socket.getAndSet(null)?.cancel()
    }
}

/** Invalidation and callback delivery share a lock, including callbacks already on another thread. */
internal class HaEventAttempt {
    val authenticated = CompletableDeferred<Unit>()
    val ended = CompletableDeferred<Unit>()
    private var active = true
    @Synchronized fun accept(callback: () -> Unit) { if (active) callback() }
    @Synchronized fun isActive() = active
    @Synchronized fun close() {
        active = false
        authenticated.cancel()
        ended.complete(Unit)
    }
}

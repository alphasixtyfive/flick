package app.flick.integration

import android.content.Context
import android.os.SystemClock
import app.flick.homeassistant.HaCredentialStore
import app.flick.homeassistant.HaCredentials
import app.flick.homeassistant.HaPolicy
import app.flick.platform.closeAsync
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** Optional outbound companion channel. Existing Home Assistant controls do not depend on it. */
class FlickBridgeClient(
    context: Context,
    scope: CoroutineScope,
    private val onCommand: suspend (BridgeCommand) -> JSONObject,
    private val status: suspend () -> JSONObject,
) {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val store = BridgeStore(context.applicationContext)
    private val credentialsStore = HaCredentialStore(context.applicationContext)
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val mutable = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutable.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private val changes = Mutex()
    private val connectionLock = Any()
    private var generation = 0L
    @Volatile private var closed = false
    private var session: Job? = null
    private var expiry: Job? = null
    private var pairingDeadline = 0L
    private var pairingOrigin: String? = null
    @Volatile private var active: Connection? = null

    init {
        worker.launch {
            try { changes.withLock { mutable.value = store.state(); reconnectLocked() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(connection = BridgeConnection.Unavailable) } }
            finally { initialized.complete(Unit) }
        }
    }

    suspend fun setEnabled(value: Boolean) = change { store.setEnabled(value) }
    suspend fun setNotificationsEnabled(value: Boolean) = change { store.setNotificationsEnabled(value) }
    suspend fun setAllowManagement(value: Boolean) = change { store.setAllowManagement(value) }
    private suspend fun change(write: () -> Unit) = withContext(Dispatchers.IO) {
        ready(); changes.withLock {
            write()
            val saved = store.state()
            mutable.update { saved.copy(paired = it.paired, pairingCode = it.pairingCode,
                pairingExpiresAtMillis = it.pairingExpiresAtMillis, connection = it.connection) }
            reconnectLocked()
        }
    }

    suspend fun beginPairing() = withContext(Dispatchers.IO) {
        ready(); changes.withLock {
            val credentials = credentialsStore.credentials() ?: error("Connect Home Assistant first")
            val origin = HaPolicy.canonicalUrl(credentials.url)
            require(store.identity(origin)?.paired != true) { "This TV is already paired" }
            stopLocked()
            store.setEnabled(true)
            store.beginPairing(origin)
            pairingOrigin = origin
            pairingDeadline = SystemClock.elapsedRealtime() + PAIRING_MILLIS
            val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
            mutable.value = store.state().copy(pairingCode = code,
                pairingExpiresAtMillis = System.currentTimeMillis() + PAIRING_MILLIS,
                connection = BridgeConnection.Connecting)
            expiry?.cancel()
            expiry = worker.launch {
                delay(PAIRING_MILLIS)
                changes.withLock {
                    if (state.value.pairingCode == code && SystemClock.elapsedRealtime() >= pairingDeadline) {
                        pairingOrigin = null; pairingDeadline = 0
                        mutable.update { it.copy(pairingCode = null, pairingExpiresAtMillis = null) }
                        reconnectLocked()
                    }
                }
            }
            reconnectLocked()
        }
    }

    suspend fun awaitInitialized() { ready() }

    /** A sticky service may restore a saved pairing, never an expired pairing prompt. */
    suspend fun recoveryAllowed(): Boolean = withContext(Dispatchers.IO) {
        if (closed) return@withContext false
        initialized.await()
        if (closed) return@withContext false
        changes.withLock {
            val credentials = credentialsStore.credentials() ?: return@withLock false
            store.state().enabled && store.identity(HaPolicy.canonicalUrl(credentials.url))?.paired == true
        }
    }

    suspend fun reconnect() = withContext(Dispatchers.IO) { ready(); changes.withLock { reconnectLocked() } }
    suspend fun forgetPairing() = withContext(Dispatchers.IO) {
        ready(); changes.withLock {
            stopLocked(); expiry?.cancel(); pairingOrigin = null; pairingDeadline = 0
            store.forget(); mutable.value = store.state()
        }
    }

    suspend fun publishStatus() = withContext(Dispatchers.IO) {
        val connection = active ?: return@withContext
        if (!state.value.paired || state.value.connection != BridgeConnection.Connected) return@withContext
        val data = status()
        if (connection.isCurrent()) connection.send("flick_tv/status", data)
    }

    suspend fun notificationAction(noticeId: String, actionId: String) {
        require(BridgeProtocol.validId(noticeId) && BridgeProtocol.validId(actionId)) { "Invalid notification action" }
        val connection = active ?: return
        if (state.value.paired && state.value.notificationsEnabled && connection.isCurrent()) {
            connection.send("flick_tv/notification_action", JSONObject().put("notification_id", noticeId).put("action_id", actionId))
        }
    }

    private suspend fun reconnectLocked() {
        stopLocked()
        val saved = store.state()
        if (!saved.enabled) { mutable.value = saved; return }
        val credentials = credentialsStore.credentials()
        if (credentials == null) { mutable.value = saved.copy(connection = BridgeConnection.HomeAssistantRequired); return }
        val origin = HaPolicy.canonicalUrl(credentials.url)
        val identity = store.identity(origin)
        val code = state.value.pairingCode?.takeIf { pairingOrigin == origin && SystemClock.elapsedRealtime() < pairingDeadline }
        mutable.value = saved.copy(paired = identity?.paired == true, pairingCode = code,
            pairingExpiresAtMillis = state.value.pairingExpiresAtMillis.takeIf { code != null }, connection = BridgeConnection.NotPaired)
        if (identity == null || (!identity.paired && code == null)) return
        val epoch = synchronized(connectionLock) { generation }
        session = worker.launch {
            var backoff = 500L
            while (isActive && isCurrent(epoch)) {
                updateConnection(epoch, BridgeConnection.Connecting)
                var healthy = false
                try { healthy = connect(credentials, identity, code, epoch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (rejected: SignInRejected) { updateConnection(epoch, BridgeConnection.Rejected); break }
                catch (_: Exception) { updateConnection(epoch, BridgeConnection.Unavailable) }
                if (!isCurrent(epoch)) break
                if (healthy) backoff = 500
                updateConnection(epoch, BridgeConnection.Unavailable)
                delay(backoff + Random.nextLong(250)); backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun stopLocked() {
        synchronized(connectionLock) { generation++; active = null }
        session?.cancelAndJoin(); session = null
    }

    private suspend fun connect(credentials: HaCredentials, identity: BridgeIdentity, code: String?, epoch: Long): Boolean = coroutineScope {
        val subscribed = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val queue = Channel<BridgeEvent>(8)
        val ids = AtomicInteger(1)
        val socket = AtomicReference<WebSocket?>()
        val authenticated = AtomicBoolean(false)
        val connected = AtomicBoolean(false)
        val alive = AtomicBoolean(true)
        val connection = Connection(epoch, identity, ids, alive) { socket.get() }
        var heartbeat: Job? = null
        val consumer = launch {
            for (event in queue) {
                if (!connection.isCurrent()) continue
                when (event) {
                    BridgeEvent.Paired -> changes.withLock {
                        if (connection.isCurrent()) {
                            store.markPaired(identity.origin)
                            expiry?.cancel(); pairingOrigin = null; pairingDeadline = 0
                            synchronized(connectionLock) {
                                if (connection.isCurrent()) {
                                    mutable.update { it.copy(paired = true, pairingCode = null, pairingExpiresAtMillis = null, connection = BridgeConnection.Connected) }
                                    active = connection
                                }
                            }
                            runCatching { connection.send("flick_tv/status", status()) }
                        }
                    }
                    is BridgeEvent.Command -> handle(event.value, connection)
                }
            }
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val current = synchronized(connectionLock) {
                    if (connection.isCurrent()) { socket.set(webSocket); true } else false
                }
                if (!current) webSocket.cancel()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val receivedAtNanos = System.nanoTime()
                if (!connection.isCurrent()) return
                try {
                    val frame = BridgeProtocol.frame(text)
                    when (frame.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", credentials.token).toString())
                        "auth_invalid" -> { subscribed.completeExceptionally(SignInRejected()); ended.complete(Unit) }
                        "auth_ok" -> {
                            check(authenticated.compareAndSet(false, true))
                            val connect = JSONObject().put("id", 1).put("type", "flick_tv/connect").put("device_id", state.value.deviceId)
                                .put("name", state.value.name).put("protocol", BridgeProtocol.VERSION).put("device_secret", identity.secret)
                            if (!state.value.paired) {
                                check(code != null && pairingOrigin == identity.origin && SystemClock.elapsedRealtime() < pairingDeadline)
                                connect.put("pairing_code", code)
                            }
                            webSocket.send(BridgeProtocol.encoded(connect))
                        }
                        "result" -> if (frame.optInt("id") == 1) {
                            check(authenticated.get())
                            if (frame.opt("success") != true) subscribed.completeExceptionally(IllegalStateException("Integration unavailable"))
                            else {
                                val result = frame.optJSONObject("result") ?: error("Invalid integration response")
                                BridgeProtocol.validateNegotiation(result)
                                connected.set(true)
                                when (result.optString("status")) {
                                    "pending" -> connection.update(BridgeConnection.Pairing)
                                    "connected" -> check(queue.trySend(BridgeEvent.Paired).isSuccess)
                                    else -> error("Invalid integration response")
                                }
                                subscribed.complete(Unit)
                            }
                        }
                        "event" -> if (frame.optInt("id") == 1) {
                            check(authenticated.get() && connected.get())
                            val event = BridgeProtocol.event(frame.getJSONObject("event"), receivedAtNanos)
                            if (!queue.trySend(event).isSuccess) {
                                if (event is BridgeEvent.Command) reply(connection, event.value.requestId, error = "TV is busy")
                                else error("Integration queue is full")
                            }
                        }
                    }
                } catch (_: Exception) { webSocket.cancel(); subscribed.completeExceptionally(IllegalStateException("Invalid integration message")); ended.complete(Unit) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!connection.isCurrent()) return
                webSocket.cancel(); ended.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connection.isCurrent()) return
                subscribed.completeExceptionally(IllegalStateException("Integration unavailable")); ended.complete(Unit)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connection.isCurrent()) ended.complete(Unit)
            }
        }
        try {
            val address = identity.origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            socket.set(client.newWebSocket(Request.Builder().url("$address/api/websocket").build(), listener))
            withTimeout(10_000) { subscribed.await() }
            val subscribedAt = System.nanoTime()
            heartbeat = launch { while (isActive) { delay(30_000); if (connection.isCurrent()) runCatching { publishStatus() } } }
            ended.await()
            System.nanoTime() - subscribedAt >= 30_000_000_000L
        } finally {
            synchronized(connectionLock) { alive.set(false); if (active === connection) active = null }
            queue.close(); consumer.cancel(); heartbeat?.cancel(); socket.getAndSet(null)?.cancel()
        }
    }

    private suspend fun handle(command: BridgeCommand, connection: Connection) {
        if (!connection.isCurrent() || !state.value.paired) return
        try {
            if (!store.claim(command.requestId)) { reply(connection, command.requestId, error = "Request was already handled"); return }
            val allowed = if (command.type == BridgeCommandType.Notify) state.value.notificationsEnabled
                else command.type == BridgeCommandType.GetStatus || state.value.allowManagement
            if (!allowed) { reply(connection, command.requestId, error = "This command is not enabled on the TV"); return }
            if (!connection.isCurrent()) return
            val remaining = command.remainingMillis()
            if (remaining == 0L) { reply(connection, command.requestId, error = "TV command expired before execution"); return }
            val result = withTimeout(remaining) { onCommand(command) }
            if (connection.isCurrent()) {
                reply(connection, command.requestId, data = result)
                if (command.type != BridgeCommandType.GetStatus) publishStatus()
            }
        } catch (timeout: TimeoutCancellationException) { reply(connection, command.requestId, error = "TV command timed out") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: BridgeCommandException) { reply(connection, command.requestId, error = error.code) }
        catch (_: Exception) { reply(connection, command.requestId, error = "TV could not complete the request") }
    }

    private fun reply(connection: Connection, requestId: String, data: JSONObject? = null, error: String? = null) {
        if (!connection.isCurrent()) return
        val value = JSONObject().put("request_id", requestId).put("success", error == null)
        if (error != null) value.put("error", error) else value.put("data", data ?: JSONObject())
        connection.send("flick_tv/result", value)
    }

    private inner class Connection(val epoch: Long, val identity: BridgeIdentity, private val ids: AtomicInteger,
        private val alive: AtomicBoolean, private val socket: () -> WebSocket?) {
        fun isCurrent(): Boolean = synchronized(connectionLock) { !closed && generation == epoch && alive.get() }
        fun update(value: BridgeConnection) = synchronized(connectionLock) {
            if (isCurrent()) mutable.update { it.copy(connection = value) }
        }
        fun send(type: String, data: JSONObject) {
            if (!isCurrent()) return
            val frame = JSONObject(data.toString()).put("id", ids.incrementAndGet()).put("type", type)
                .put("device_id", state.value.deviceId).put("device_secret", identity.secret)
            if (socket()?.send(BridgeProtocol.encoded(frame)) != true) socket()?.cancel()
        }
    }
    private fun updateConnection(epoch: Long, connection: BridgeConnection) = synchronized(connectionLock) {
        if (!closed && generation == epoch) mutable.update { it.copy(connection = connection) }
    }
    private fun isCurrent(epoch: Long) = synchronized(connectionLock) { !closed && generation == epoch }
    private suspend fun ready() { check(!closed) { "Integration is closed" }; initialized.await(); check(!closed) { "Integration is closed" } }
    fun close() {
        synchronized(connectionLock) {
            if (closed) return
            closed = true; generation++; active = null
        }
        initialized.complete(Unit)
        client.closeAsync {
            expiry?.cancel(); session?.cancel(); worker.cancel()
        }
    }
    private class SignInRejected : Exception()
    companion object { private const val PAIRING_MILLIS = 300_000L }
}

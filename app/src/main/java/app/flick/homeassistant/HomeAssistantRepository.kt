package app.flick.homeassistant

import android.content.Context
import app.flick.platform.closeAsync
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Selected Home Assistant controls with live state updates and private credentials. */
class HomeAssistantRepository(context: Context, scope: CoroutineScope) {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO)
    private val store = HaCredentialStore(context.applicationContext)
    private val client = OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS).writeTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(4, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val eventsClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val transport = HaTransport(client)
    private val initialized = CompletableDeferred<Unit>()
    private val settingsLock = Mutex()
    private val reads = Semaphore(4)
    private val actionLocks = ConcurrentHashMap<String, Mutex>()
    private val actions = ConcurrentHashMap<Job, String>()
    private val versions = ConcurrentHashMap<String, AtomicLong>()
    private val generation = AtomicLong()
    private val observations = MutableSharedFlow<HaControl>(extraBufferCapacity = 32)
    private val mutableConfiguration = MutableStateFlow(HaConfiguration())
    private val mutableControls = MutableStateFlow<List<HaControl>>(emptyList())
    private val mutableConnectionState = MutableStateFlow(HaConnectionState.Unknown)
    private val mutableCredentialRevision = MutableStateFlow(0L)
    private var health = HaConnectionHealth()
    private val displayMonitor = HaDisplayPowerMonitor()
    private val mutableDisplayPower = MutableStateFlow(HaDisplayPower.Disabled)
    val displayPower: StateFlow<HaDisplayPower> = mutableDisplayPower.asStateFlow()
    private val mutableDisplayPowerInterruptions = MutableStateFlow(0L)
    /** Durable across rapid Off→On events; StateFlow conflation must not lose a session boundary. */
    val displayPowerInterruptions: StateFlow<Long> = mutableDisplayPowerInterruptions.asStateFlow()
    val connectionState: StateFlow<HaConnectionState> = mutableConnectionState.asStateFlow()
    val credentialRevision: StateFlow<Long> = mutableCredentialRevision.asStateFlow()
    val configuration: StateFlow<HaConfiguration> = mutableConfiguration.asStateFlow()
    val controls: StateFlow<List<HaControl>> = mutableControls.asStateFlow()
    @Volatile private var credentials: HaCredentials? = null
    @Volatile private var closed = false
    private var session: Job? = null
    private var stream: HaEventStream? = null

    init {
        worker.launch {
            try {
                credentials = store.credentials()
                val selected = store.controls()
                mutableConfiguration.value = HaConfiguration(store.url(), credentials != null, selected, store.displayPowerEntity())
                mutableControls.value = selected.map { initial(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                credentials = null
                mutableConfiguration.value = HaConfiguration()
                mutableControls.value = emptyList()
            } finally { initialized.complete(Unit) }
            restartSession()
        }
    }

    suspend fun configure(url: String, token: String) = withContext(Dispatchers.IO) {
        ready()
        settingsLock.withLock {
            if (url.isBlank() && token.isBlank()) {
                store.clear()
                synchronized(this@HomeAssistantRepository) {
                    actions.keys.forEach { it.cancel() }
                    credentials = null
                    generation.incrementAndGet()
                    mutableConfiguration.value = HaConfiguration()
                    mutableControls.value = emptyList()
                    mutableConnectionState.value = HaConnectionState.NotConfigured
                    mutableCredentialRevision.value++
                }
                restartSession()
                return@withLock
            }
            val address = HaPolicy.canonicalUrl(url)
            val secret = token.trim().ifEmpty { credentials?.takeIf { it.url == address }?.token.orEmpty() }
            if (!HaPolicy.validToken(secret)) throw HaException("Enter a Home Assistant access token")
            val replacement = HaCredentials(address, secret)
            validateApi(replacement)
            val selected = if (configuration.value.url == address) configuration.value.controls else emptyList()
            val displayEntity = configuration.value.displayPowerEntity.takeIf { configuration.value.url == address }
            try { store.save(replacement, selected, displayEntity) } catch (_: Exception) { throw HaException("Home Assistant sign-in could not be saved") }
            synchronized(this@HomeAssistantRepository) {
                actions.keys.forEach { it.cancel() }
                credentials = replacement
                generation.incrementAndGet()
                mutableConfiguration.value = HaConfiguration(address, true, selected, displayEntity)
                mutableControls.value = selected.map { initial(it) }
                mutableCredentialRevision.value++
            }
            restartSession(authenticatedHttp = true)
        }
    }

    /** Import can check credentials before changing any other feature's settings. No writes occur. */
    suspend fun validateConfiguration(url: String, token: String) = withContext(Dispatchers.IO) {
        ready()
        if (url.isBlank() && token.isBlank()) return@withContext
        val address = HaPolicy.canonicalUrl(url)
        val secret = token.trim().ifEmpty { credentials?.takeIf { it.url == address }?.token.orEmpty() }
        if (!HaPolicy.validToken(secret)) throw HaException("Enter a Home Assistant access token")
        validateApi(HaCredentials(address, secret))
    }

    suspend fun discover(): List<HaEntity> = discoverEntities(HaPolicy::domain)

    suspend fun discoverDisplayPlayers(): List<HaEntity> = discoverEntities { id ->
        HaDisplayPowerPolicy.entityId(id)?.let { "media_player" }
    }

    private suspend fun discoverEntities(domainFor: (String) -> String?): List<HaEntity> = withContext(Dispatchers.IO) {
        ready()
        val (connection, epoch) = snapshot()
        val values = try {
            parseArray(transport.request(connection, "/api/states", maximumBytes = 4 * 1024 * 1024))
        } catch (error: HaException) {
            if (error.authenticationRejected) reportHttp(epoch, available = false, rejected = true)
            throw error
        }
        reportHttp(epoch, available = true)
        if (epoch != generation.get()) throw HaException("Home Assistant changed; refresh the entity list")
        (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("entity_id")
            val domain = domainFor(id) ?: return@mapNotNull null
            val name = HaPolicy.text(item.optJSONObject("attributes")?.optString("friendly_name").orEmpty())
                .ifEmpty { id.substringAfter('.').replace('_', ' ') }
            HaEntity(id, name, domain)
        }.distinctBy { it.entityId }.sortedWith(compareBy({ it.domain }, { it.name.lowercase() }))
    }

    suspend fun saveControls(values: List<HaControlSpec>) = withContext(Dispatchers.IO) {
        ready()
        val selected = HaPolicy.controls(values)
        settingsLock.withLock {
            try { store.saveControls(selected) } catch (_: Exception) { throw HaException("The controls could not be saved") }
            synchronized(this@HomeAssistantRepository) {
                actions.keys.forEach { it.cancel() }
                generation.incrementAndGet()
                val previous = controls.value.associateBy { it.id }
                mutableConfiguration.value = configuration.value.copy(controls = selected)
                mutableControls.value = selected.map { spec -> previous[spec.entityId]?.copy(name = spec.name, pending = false) ?: initial(spec) }
            }
        }
        restartSession(keepHttp = true)
    }

    suspend fun saveDisplayPowerEntity(value: HaControlSpec?) = withContext(Dispatchers.IO) {
        ready()
        val selected = HaDisplayPowerPolicy.selection(value)
        settingsLock.withLock {
            requireCredentials()
            try { store.saveDisplayPowerEntity(selected) } catch (_: Exception) {
                throw HaException("The physical TV selection could not be saved")
            }
            synchronized(this@HomeAssistantRepository) {
                actions.keys.forEach { it.cancel() }
                generation.incrementAndGet()
                mutableConfiguration.value = configuration.value.copy(displayPowerEntity = selected)
                mutableControls.update { list -> list.map { it.copy(pending = false) } }
            }
            restartSession(keepHttp = true)
        }
    }

    /** Retry uses the saved, origin-bound credentials and preserves the selected controls. */
    suspend fun retry() = withContext(Dispatchers.IO) {
        ready()
        settingsLock.withLock {
            synchronized(this@HomeAssistantRepository) {
                if (credentials == null) return@withLock
                actions.keys.forEach { it.cancel() }
                generation.incrementAndGet()
                mutableControls.update { list -> list.map { it.copy(pending = false) } }
            }
            restartSession()
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        ready()
        val (connection, epoch) = synchronized(this@HomeAssistantRepository) {
            credentials?.let { it to generation.get() }
        } ?: return@withContext
        coroutineScope {
            configuration.value.controls.map { spec -> async { reads.withPermit { readOne(connection, spec, epoch) } } }.awaitAll()
        }
        Unit
    }

    suspend fun setPower(id: String, on: Boolean) = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        actions[job] = id
        try {
            ready()
            actionLocks.getOrPut(id) { Mutex() }.withLock {
                val spec = requireSelected(id)
                val domain = HaPolicy.domain(id)
                if (domain !in HaPolicy.powerDomains) throw HaException("This control does not support switching")
                val (connection, epoch) = snapshot()
                advance(id)
                pending(id, true, epoch = epoch)
                try {
                    withTimeout(6500) {
                        HaCommandConfirmation.await(
                            reported = observations,
                            matches = { it.id == id && HaPolicy.confirms(it.state, on) },
                            send = {
                                transport.request(connection, "/api/services/$domain/${if (on) "turn_on" else "turn_off"}",
                                    JSONObject().put("entity_id", id).toString())
                                reportHttp(epoch, available = true)
                            },
                            readConfirmed = {
                                readOne(connection, spec, epoch)
                                val actual = controls.value.firstOrNull { it.id == id }
                                actual?.error == null && HaPolicy.confirms(actual?.state, on)
                            }
                        )
                    }
                    pending(id, false, epoch = epoch)
                } catch (_: TimeoutCancellationException) {
                    pending(id, false, "The new state was not confirmed", epoch)
                    throw HaException("The new state was not confirmed")
                } catch (cancelled: CancellationException) {
                    pending(id, false, if (closed) null else "The new state was not confirmed", epoch)
                    throw cancelled
                } catch (error: Exception) {
                    if ((error as? HaException)?.authenticationRejected == true) reportHttp(epoch, available = false, rejected = true)
                    pending(id, false, errorMessage(error), epoch)
                    throw HaException(errorMessage(error))
                }
            }
        } finally { actions.remove(job) }
    }

    suspend fun runScene(id: String) = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        actions[job] = id
        try {
            ready()
            actionLocks.getOrPut(id) { Mutex() }.withLock {
                requireSelected(id)
                val domain = HaPolicy.domain(id)
                if (domain !in HaPolicy.actionDomains) throw HaException("This control is not a scene or script")
                val (connection, epoch) = snapshot()
                pending(id, true, epoch = epoch)
                try {
                    transport.request(connection, "/api/services/$domain/turn_on", JSONObject().put("entity_id", id).toString())
                    reportHttp(epoch, available = true)
                    pending(id, false, epoch = epoch)
                } catch (cancelled: CancellationException) { pending(id, false, epoch = epoch); throw cancelled }
                catch (error: Exception) {
                    if ((error as? HaException)?.authenticationRejected == true) reportHttp(epoch, available = false, rejected = true)
                    pending(id, false, errorMessage(error), epoch)
                    throw HaException(errorMessage(error))
                }
            }
        } finally { actions.remove(job) }
    }

    private suspend fun readOne(connection: HaCredentials, spec: HaControlSpec, epoch: Long) {
        val version = versions.getOrPut(spec.entityId) { AtomicLong() }.get()
        try {
            val response = transport.request(connection, "/api/states/${spec.entityId}")
            val state = withContext(Dispatchers.Default) { JSONObject(response) }
            reportHttp(epoch, available = true)
            observed(spec.entityId, state, epoch, version)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if ((error as? HaException)?.authenticationRejected == true) reportHttp(epoch, available = false, rejected = true)
            synchronized(this@HomeAssistantRepository) {
                if (generation.get() == epoch && version == versions[spec.entityId]?.get()) {
                    mutableControls.update { list -> list.map { if (it.id == spec.entityId) it.copy(error = errorMessage(error)) else it } }
                }
            }
        }
    }

    @Synchronized
    private fun observed(id: String, value: JSONObject?, epoch: Long, expectedVersion: Long? = null) {
        if (closed || generation.get() != epoch) return
        if (id == configuration.value.displayPowerEntity?.entityId && expectedVersion == null) {
            displayMonitor.observe(id, value?.optString("state"))
            publishDisplayPower()
        }
        if (expectedVersion != null && versions[id]?.get() != expectedVersion) return
        val spec = configuration.value.controls.firstOrNull { it.entityId == id } ?: return
        val state = value?.optString("state")?.let { HaPolicy.text(it, 80) }?.takeIf { it.isNotEmpty() }
        val brightness = value?.optJSONObject("attributes")?.optInt("brightness", -1)?.takeIf { it in 0..255 }
            ?.let { ((it / 255.0) * 100).toInt() }
        versions.getOrPut(id) { AtomicLong() }.incrementAndGet()
        val current = controls.value.firstOrNull { it.id == id }
        val next = HaControl(id, spec.name, id.substringBefore('.'), state, brightness,
            pending = current?.pending ?: false, error = if (state == null || state in setOf("unknown", "unavailable")) "Unavailable" else null)
        mutableControls.update { list -> list.map { if (it.id == id) next else it } }
        observations.tryEmit(next)
    }

    @Synchronized
    private fun advance(id: String) { versions.getOrPut(id) { AtomicLong() }.incrementAndGet() }

    @Synchronized
    private fun restartSession(authenticatedHttp: Boolean = false, keepHttp: Boolean = false) {
        session?.cancel()
        stream = null
        displayMonitor.select(configuration.value.displayPowerEntity?.entityId)
        publishDisplayPower()
        val connection = credentials
        if (connection == null || closed) {
            mutableConnectionState.value = HaConnectionState.NotConfigured
            return
        }
        val epoch = generation.get()
        val httpConfirmed = authenticatedHttp || (keepHttp && health.httpAvailable)
        health = HaConnectionHealth(httpAvailable = httpConfirmed)
        mutableConnectionState.value = health.state
        session = worker.launch {
            val events = if (configuration.value.observedEntityIds().isNotEmpty()) HaEventStream(eventsClient, this, connection,
                selected = { configuration.value.observedEntityIds() },
                observed = { id, value -> observed(id, value, epoch) },
                ready = {
                    reportEvents(epoch, available = true)
                    refreshDisplayPower(connection, epoch)
                    refresh()
                },
                unavailable = { rejected -> reportEvents(epoch, available = false, rejected = rejected) }) else null
            val current = synchronized(this@HomeAssistantRepository) {
                if (closed || epoch != generation.get()) false
                else { stream = events; true }
            }
            if (!current) { events?.close(); return@launch }
            events?.let { launch { it.run() } }
            if (!httpConfirmed) checkConnection(connection, epoch)
            if (connectionState.value == HaConnectionState.Connected) refresh()
            while (isActive) {
                delay(15_000)
                checkConnection(connection, epoch)
                if (events?.connected != true && connectionState.value == HaConnectionState.Connected) refresh()
            }
        }
    }

    /** One bounded read after subscription; live updates use the existing event stream. */
    private suspend fun refreshDisplayPower(connection: HaCredentials, epoch: Long) {
        val selection = synchronized(this@HomeAssistantRepository) {
            if (closed || epoch != generation.get()) null
            else configuration.value.displayPowerEntity?.let { spec ->
                displayMonitor.snapshotRevision()?.let { spec.entityId to it }
            }
        } ?: return
        try {
            val response = JSONObject(transport.request(connection, "/api/states/${selection.first}"))
            synchronized(this@HomeAssistantRepository) {
                if (!closed && epoch == generation.get()) {
                    displayMonitor.observe(selection.first, response.optString("state"), selection.second)
                    publishDisplayPower()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if ((error as? HaException)?.authenticationRejected == true) reportHttp(epoch, available = false, rejected = true)
            // Keep Unknown. A missing or unavailable TV must never imply it stayed on.
        }
    }

    private suspend fun checkConnection(connection: HaCredentials, epoch: Long) {
        try {
            validateApi(connection)
            reportHttp(epoch, available = true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            reportHttp(epoch, available = false, rejected = (error as? HaException)?.authenticationRejected == true)
        }
    }

    private suspend fun validateApi(connection: HaCredentials) {
        val response = transport.request(connection, "/api/", maximumBytes = 16 * 1024)
        val valid = runCatching { JSONObject(response).optString("message") == "API running." }.getOrDefault(false)
        if (!valid) throw HaException("The server did not return a Home Assistant API response")
    }

    @Synchronized
    private fun reportHttp(epoch: Long, available: Boolean, rejected: Boolean = false) {
        if (closed || epoch != generation.get()) return
        health = health.httpResult(available, rejected)
        if (rejected) {
            displayMonitor.connection(false)
            publishDisplayPower()
        }
        mutableConnectionState.value = health.state
    }

    @Synchronized
    private fun reportEvents(epoch: Long, available: Boolean, rejected: Boolean = false) {
        if (closed || epoch != generation.get()) return
        health = health.eventsResult(available, rejected)
        displayMonitor.connection(available)
        publishDisplayPower()
        mutableConnectionState.value = health.state
    }

    private fun publishDisplayPower() {
        mutableDisplayPower.value = displayMonitor.state
        mutableDisplayPowerInterruptions.value = displayMonitor.interruptions
    }

    @Synchronized
    private fun pending(id: String, value: Boolean, error: String? = null, epoch: Long? = null) {
        if (closed || (epoch != null && epoch != generation.get())) return
        mutableControls.update { list -> list.map { if (it.id == id) it.copy(pending = value, error = error) else it } }
    }

    private fun initial(spec: HaControlSpec) = HaControl(spec.entityId, spec.name, spec.entityId.substringBefore('.'))
    private fun requireCredentials() = credentials ?: throw HaException("Connect Home Assistant first")
    @Synchronized
    private fun snapshot() = requireCredentials() to generation.get()
    private fun requireSelected(id: String) = configuration.value.controls.firstOrNull { it.entityId == id }
        ?: throw HaException("This control is not selected")
    private fun parseArray(value: String) = try { JSONArray(value) } catch (_: Exception) { throw HaException("Home Assistant returned an invalid response") }
    private fun errorMessage(error: Exception) = (error as? HaException)?.message ?: "Home Assistant is unavailable"
    private suspend fun ready() { if (closed) throw HaException("Home Assistant is closed"); initialized.await(); if (closed) throw HaException("Home Assistant is closed") }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true; initialized.complete(Unit); credentials = null
        generation.incrementAndGet()
        mutableConnectionState.value = HaConnectionState.NotConfigured
        displayMonitor.connection(false)
        publishDisplayPower()
        client.closeAsync {
            actions.keys.forEach { it.cancel() }
            session?.cancel(); stream?.close(); worker.cancel()
        }
    }
}

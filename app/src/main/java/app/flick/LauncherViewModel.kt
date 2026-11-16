package app.flick

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.flick.apps.AndroidCatalogue
import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.home.HomePolicy
import app.flick.home.HomeState
import app.flick.homeassistant.HaControl
import app.flick.homeassistant.HaControlSpec
import app.flick.homeassistant.HaPolicy
import app.flick.homeassistant.HomeAssistantRepository
import app.flick.profiles.Profile
import app.flick.profiles.ProfileSecurity
import app.flick.profiles.withChildApp
import app.flick.settings.LauncherSettings
import app.flick.settings.SettingsCodec
import app.flick.settings.SettingsStore
import app.flick.wallpaper.WallpaperRepository
import app.flick.wallpaper.WallpaperSettings
import app.flick.weather.WeatherRepository
import app.flick.weather.WeatherLocation
import app.flick.weather.refreshWeather
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as FlickApplication).runtime
    private val store = runtime.store
    private val profiles = runtime.profiles
    private val catalogue = runtime.catalogue
    private val weather = WeatherRepository()
    private val homeAssistant = runtime.homeAssistant
    private val wallpapers = runtime.wallpapers
    private val mutable = MutableStateFlow(HomeState(settings = store.state.value, profileSession = runtime.profileSession.state.value))
    val state = mutable.asStateFlow()
    private val profileLock = runtime.profileLock
    private val privilegedJobs = runtime.privilegedJobs
    private var switchingProfile: Boolean
        get() = runtime.switchingProfile
        set(value) { runtime.switchingProfile = value }
    private var allApps = emptyList<AppEntry>()
    private var allContinue = emptyList<ContinueItem>()
    private var placeSearch: Job? = null
    private var placeSearchGeneration = 0L
    private var weatherLoop: Job? = null
    private var displayedWeatherLocation: WeatherLocation? = null
    private var catalogueRead: Job? = null
    private var continueRead: Job? = null
    private var active = true

    init {
        viewModelScope.launch { runtime.profileSession.state.collect { session ->
            mutable.update { it.copy(profileSession = session, profileError = null, profileErrorTarget = null) }
            updateCatalogue()
            refreshContinue()
        } }
        viewModelScope.launch { store.state.collect { settings ->
            runtime.reconcileProfileSession()
            val previous = mutable.value.settings
            mutable.update { it.copy(settings = settings, profileSession = runtime.profileSession.state.value) }
            updateCatalogue()
            if (previous.activeProfileId != settings.activeProfileId || previous.showContinue != settings.showContinue ||
                HomePolicy.canManage(previous) != HomePolicy.canManage(settings)) refreshContinue()
        } }
        viewModelScope.launch { store.requiresRecovery.collect { required -> mutable.update { it.copy(recoveryRequired = required) } } }
        viewModelScope.launch { homeAssistant.configuration.collect { value -> mutable.update { it.copy(haConfiguration = value) } } }
        viewModelScope.launch { homeAssistant.connectionState.collect { value -> mutable.update { it.copy(haConnectionState = value) } } }
        viewModelScope.launch { homeAssistant.displayPower.collect { value -> mutable.update { it.copy(displayPower = value) } } }
        viewModelScope.launch { homeAssistant.controls.collect { value -> mutable.update { it.copy(homeControls = value) } } }
        viewModelScope.launch { runtime.bridge.state.collect { value -> mutable.update { it.copy(bridge = value) } } }
        viewModelScope.launch { runtime.notifications.state.collect { value -> mutable.update { it.copy(notifications = value) } } }
        viewModelScope.launch { wallpapers.settings.collect { value -> mutable.update { it.copy(wallpaperSettings = value) } } }
        viewModelScope.launch { wallpapers.current.collect { value -> mutable.update { it.copy(wallpaperUri = value?.uri) } } }
        refreshCatalogue()
    }

    fun setActive(active: Boolean) {
        this.active = active
        wallpapers.setActive(active)
        weatherLoop?.cancel()
        if (active) {
            refreshCatalogue()
            refreshContinue()
            weatherLoop = viewModelScope.launch {
                store.state.map { settings -> settings.weatherLocation.takeIf { settings.showWeather } }.distinctUntilChanged().collectLatest { location ->
                    if (displayedWeatherLocation != location) {
                        displayedWeatherLocation = location
                        mutable.update { it.copy(weather = null) }
                    }
                    if (location != null) refreshWeather(
                        load = { weather.conditions(location) },
                        onReading = { reading -> mutable.update { it.copy(weather = reading) } },
                    )
                }
            }
        } else continueRead?.cancel()
    }

    fun refreshCatalogue() {
        if (catalogueRead?.isActive == true) return
        catalogueRead = task {
            allApps = catalogue.apps()
            updateCatalogue()
            mutable.update { it.copy(loading = false) }
        }
    }

    private fun refreshContinue() {
        continueRead?.cancel()
        val settings = store.state.value
        if (!active || !state.value.continueAccess || !settings.showContinue || !adult()) {
            allContinue = emptyList()
            updateCatalogue()
            return
        }
        val profileId = settings.activeProfileId
        continueRead = task {
            val items = catalogue.continueItems()
            if (store.state.value.activeProfileId == profileId && adult()) {
                allContinue = items
                updateCatalogue()
            }
        }
    }

    private fun updateCatalogue() {
        val settings = store.state.value
        mutable.update { it.copy(
            apps = if (runtime.profileSession.profile(settings) == null) emptyList() else HomePolicy.visibleApps(settings, allApps),
            continueItems = if (!adult() || !settings.showContinue) emptyList() else
                allContinue.filter { row -> HomePolicy.continueKey(row) !in settings.removedContinue },
        ) }
    }

    fun launchApp(app: AppEntry) {
        if (state.value.loading || !runtime.canLaunch(app.packageName)) return
        runCatching { catalogue.launch(app.packageName) }.onFailure { message("This app could not be opened") }
    }

    fun openContinue(item: ContinueItem) {
        if (!adult() || state.value.continueItems.none { it.id == item.id }) return
        runCatching { catalogue.open(item) }.onFailure { message("This item is unavailable. Open its app instead.") }
    }

    fun updateSettings(before: LauncherSettings, settings: LauncherSettings) {
        editSettings { current -> SettingsCodec.mergePresentation(current, before, settings) }
    }

    fun reorderApps(profileId: String, order: List<String>) {
        val generation = runtime.profileSession.state.value.generation
        if (switchingProfile || !runtime.profileSession.canEditAppOrder(store.state.value, profileId, generation)) return
        task {
            profileLock.withLock {
                store.update { current ->
                    check(!switchingProfile && runtime.profileSession.canEditAppOrder(current, profileId, generation)) {
                        "Choose this profile again"
                    }
                    HomePolicy.reorderApps(current, profileId, order, allApps)
                }
            }
        }
    }

    fun removeContinue(item: ContinueItem) {
        editSettings { current -> current.copy(removedContinue = (current.removedContinue + HomePolicy.continueKey(item)).takeLastBounded(250)) }
    }

    fun chooseProfile(profile: Profile, pin: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (switchingProfile) {
                onComplete(Result.failure(IllegalStateException("A profile change is already in progress")))
                return@launch
            }
            switchingProfile = true
            val job = currentCoroutineContext().job
            runtime.profileSelection = job
            val generation = runtime.profileSession.state.value.generation
            var cancellation: CancellationException? = null
            val result = try {
                val pending = privilegedJobs.toList()
                pending.forEach { it.cancel() }
                pending.joinAll()
                profileLock.withLock {
                    val settings = store.state.value
                    val target = settings.profiles.firstOrNull { it.id == profile.id }
                        ?: error("This profile no longer exists")
                    if (runtime.profileSession.profile(settings)?.id == target.id) return@withLock
                    val accepted = withContext(Dispatchers.Default) { profiles.verify(target, pin) }
                    check(accepted) { "PIN not accepted" }
                    currentCoroutineContext().ensureActive()
                    check(generation == runtime.profileSession.state.value.generation) { "Choose a profile again" }
                    store.save(settings.copy(activeProfileId = target.id))
                    check(runtime.profileSession.authorize(store.state.value, target, generation)) { "Choose a profile again" }
                }
                mutable.update { it.copy(profileError = null, profileErrorTarget = null) }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                Result.failure(IllegalStateException("Profile change was cancelled. Try again."))
            } catch (error: Exception) {
                if (generation == runtime.profileSession.state.value.generation) mutable.update {
                    it.copy(profileError = error.message?.take(160) ?: "This profile could not be opened", profileErrorTarget = profile.id)
                }
                Result.failure(error)
            } finally {
                if (runtime.profileSelection === job) runtime.profileSelection = null
                switchingProfile = false
                runtime.reconcileProfileSession()
            }
            onComplete(result)
            cancellation?.let { throw it }
        }
    }

    fun cancelProfileSelection() {
        runtime.profileSession.cancelSelection()
        runtime.profileSelection?.cancel()
    }

    fun searchPlaces(query: String) {
        if (!adult()) return
        val generation = ++placeSearchGeneration
        placeSearch?.cancel()
        placeSearch = null
        val searchQuery = query.trim()
        if (searchQuery.length < 2) {
            mutable.update { it.copy(searchingPlaces = false, places = emptyList()) }
            return
        }
        placeSearch = task {
            mutable.update { it.copy(searchingPlaces = true, places = emptyList()) }
            try {
                val results = weather.search(searchQuery)
                if (generation == placeSearchGeneration) mutable.update { it.copy(places = results) }
            } finally {
                if (generation == placeSearchGeneration) mutable.update { it.copy(searchingPlaces = false) }
            }
        }
    }

    fun rotateWallpaper() { if (runtime.profileSession.profile(store.state.value) != null) wallpapers.next() }
    fun saveWallpaperSettings(before: WallpaperSettings, settings: WallpaperSettings) {
        privilegedTask { wallpapers.saveChanges(before, settings) }
    }
    fun selectWallpaperFolder(uri: String) { privilegedTask { wallpapers.selectFolder(uri) } }
    fun addWallpaperPhotos(uris: List<String>) { privilegedTask { wallpapers.addSelectedPhotos(uris) } }
    fun configureHomeAssistant(url: String, token: String) { haTask {
        mutable.update { it.copy(haDiscoveryLoaded = false, haEntities = emptyList()) }
        homeAssistant.configure(url, token)
    } }
    suspend fun discoverDisplayPlayers() = runtime.withAdultSession { homeAssistant.discoverDisplayPlayers() }
    suspend fun saveDisplayPowerEntity(value: HaControlSpec?) = runtime.withAdultSession { homeAssistant.saveDisplayPowerEntity(value) }
    fun retryHomeAssistant() { haTask { homeAssistant.retry() } }
    fun pairHomeAssistant() { privilegedTask { runtime.bridge.beginPairing(); runtime.notifications.enableBackground(true) } }
    fun forgetIntegration() { privilegedTask {
        runtime.bridge.forgetPairing()
        runtime.notifications.enableBackground(false)
        runtime.notifications.dismissAll()
    } }
    fun setIntegrationEnabled(enabled: Boolean) { privilegedTask {
        runtime.bridge.setEnabled(enabled)
        runtime.notifications.enableBackground(enabled)
    } }
    fun setNotificationsEnabled(enabled: Boolean) { privilegedTask { runtime.bridge.setNotificationsEnabled(enabled) } }
    fun setLauncherManagement(enabled: Boolean) { privilegedTask { runtime.bridge.setAllowManagement(enabled) } }
    fun clearProfileError() { mutable.update { it.copy(profileError = null, profileErrorTarget = null) } }
    fun discoverHomeEntities() { haTask {
        mutable.update { it.copy(haDiscoveryLoaded = false, haEntities = emptyList()) }
        val entities = homeAssistant.discover()
        mutable.update { it.copy(haEntities = entities, haDiscoveryLoaded = true) }
    } }
    fun saveHomeControls(controls: List<HaControlSpec>, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!adult() || switchingProfile) {
                onComplete(Result.failure(IllegalStateException("Choose an adult profile first")))
                return@launch
            }
            if (state.value.haBusy) {
                onComplete(Result.failure(IllegalStateException("Wait for the current Home Assistant action to finish")))
                return@launch
            }
            val job = currentCoroutineContext().job
            privilegedJobs.add(job)
            mutable.update { it.copy(haBusy = true) }
            val result = try {
                homeAssistant.saveControls(controls)
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                onComplete(Result.failure(IllegalStateException("Saving was cancelled. Try again.")))
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                mutable.update { it.copy(haBusy = false) }
                privilegedJobs.remove(job)
            }
            onComplete(result)
        }
    }
    fun setHomePower(control: HaControl, on: Boolean) { privilegedTask { homeAssistant.setPower(control.id, on) } }
    fun runHomeScene(control: HaControl) { privilegedTask { homeAssistant.runScene(control.id) } }
    fun canManage() = adult()
    fun requestHome() { mutable.update { it.copy(homeRequestVersion = it.homeRequestVersion + 1) } }
    fun setContinueAccess(granted: Boolean) {
        val changed = state.value.continueAccess != granted
        mutable.update { it.copy(continueAccess = granted) }
        if (changed) refreshContinue()
    }
    fun canUninstall(app: AppEntry) = adult() && catalogue.canUninstall(app)
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun message(value: String) { mutable.update { it.copy(message = value) } }
    fun saveProfilePhoto(profile: Profile, uri: String?) {
        editSettings { settings -> settings.copy(profiles = settings.profiles.map { if (it.id == profile.id) it.copy(avatar = uri) else it }) }
    }
    fun saveProfileWallpaper(profile: Profile, uri: String?) {
        editSettings { settings -> settings.copy(profiles = settings.profiles.map { if (it.id == profile.id) it.copy(wallpaperUri = uri) else it }) }
    }

    fun setProfileApp(profile: Profile, app: String, allowed: Boolean, onComplete: (Result<Unit>) -> Unit) {
        val job = privilegedTask {
            val result = try {
                profileLock.withLock {
                    store.update { current ->
                        check(adult() && !switchingProfile) { "Choose an adult profile first" }
                        current.withChildApp(profile.id, app, allowed)
                    }
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                message("App access change was interrupted. Check the saved switches.")
                onComplete(Result.failure(IllegalStateException("The app change was cancelled. Check its saved state.")))
                throw cancelled
            } catch (error: Exception) { Result.failure(error) }
            result.onFailure { message("App access could not be saved. Check the saved switches.") }
            onComplete(result)
        }
        if (job == null) onComplete(Result.failure(IllegalStateException("Choose an adult profile first")))
    }

    fun saveProfile(profile: Profile, pin: String, onComplete: (Result<Unit>) -> Unit) {
        profileEdit(onComplete) { settings ->
            val previous = settings.profiles.firstOrNull { it.id == profile.id }
            val candidate = profile.copy(pinSalt = previous?.pinSalt, pinHash = previous?.pinHash,
                allowedApps = previous?.allowedApps ?: profile.allowedApps,
                avatar = if (previous != null) previous.avatar else profile.avatar,
                wallpaperUri = if (previous != null) previous.wallpaperUri else profile.wallpaperUri,
                appOrder = previous?.appOrder ?: profile.appOrder)
            val protected = if (!candidate.child && pin.isNotEmpty()) withContext(Dispatchers.Default) {
                ProfileSecurity.protect(candidate, pin)
            } else candidate
            val people = if (previous == null) settings.profiles + protected else settings.profiles.map { if (it.id == profile.id) protected else it }
            require(people.any { !it.child }) { "Keep at least one adult profile" }
            settings.copy(profiles = people)
        }
    }

    fun deleteProfile(profile: Profile, onComplete: (Result<Unit>) -> Unit) {
        profileEdit(onComplete) { settings ->
            val people = settings.profiles.filterNot { it.id == profile.id }
            require(people.any { !it.child }) { "Keep at least one adult profile" }
            settings.copy(profiles = people, activeProfileId = settings.activeProfileId.takeIf { id -> people.any { it.id == id } }
                ?: people.first { !it.child }.id)
        }
    }

    private fun profileEdit(onComplete: (Result<Unit>) -> Unit, edit: suspend (LauncherSettings) -> LauncherSettings) {
        val job = privilegedTask {
            val owner = currentCoroutineContext().job
            val authorizedId = runtime.profileSession.profile(store.state.value)?.id
            val generation = runtime.profileSession.state.value.generation
            switchingProfile = true
            val result = try {
                privilegedJobs.toList().filterNot { it === owner }.forEach { it.cancelAndJoin() }
                profileLock.withLock {
                    check(adult()) { "Choose an adult profile first" }
                    val updated = edit(store.state.value)
                    currentCoroutineContext().ensureActive()
                    check(adult() && generation == runtime.profileSession.state.value.generation) { "Choose an adult profile first" }
                    store.save(updated)
                    // An already-authorized adult can update their own PIN; a fallback adult needs a new unlock.
                    if (updated.activeProfileId == authorizedId) {
                        val selected = updated.profiles.first { it.id == authorizedId }
                        check(runtime.profileSession.authorize(store.state.value, selected, generation)) { "Choose a profile again" }
                    }
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                onComplete(Result.failure(IllegalStateException("Saving was cancelled. Try again.")))
                throw cancelled
            }
            catch (error: Exception) { Result.failure(error) }
            finally {
                privilegedJobs.remove(owner)
                switchingProfile = false
                runtime.reconcileProfileSession()
            }
            onComplete(result)
        }
        if (job == null) onComplete(Result.failure(IllegalStateException("Choose an adult profile first")))
    }

    suspend fun importSettings(settings: LauncherSettings, wallpaper: WallpaperSettings?,
        haUrl: String? = null, haToken: String? = null, haControls: List<HaControlSpec> = emptyList()) {
        val job = requireNotNull(currentCoroutineContext()[Job])
        privilegedJobs.add(job)
        try {
            profileLock.withLock {
                check(adult() && !switchingProfile) { "Choose an adult profile before importing setup" }
                SettingsCodec.read(SettingsCodec.write(settings))
                val validated = wallpaper?.let { wallpapers.validateForImport(it) }
                val selected = HaPolicy.controls(haControls)
                if (haUrl != null && haToken != null) homeAssistant.validateConfiguration(haUrl, haToken)
                if (haUrl != null && haToken != null) {
                    homeAssistant.configure(haUrl, haToken)
                    homeAssistant.saveControls(selected)
                }
                validated?.let { wallpapers.save(it) }
                store.save(settings)
            }
        } finally { privilegedJobs.remove(job) }
    }

    private fun editSettings(transform: (LauncherSettings) -> LauncherSettings) = privilegedTask {
        profileLock.withLock {
            store.update { current ->
                check(adult()) { "Choose an adult profile first" }
                transform(current)
            }
        }
    }

    private fun adult() = runtime.canManage()
    private fun task(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend () -> Unit): Job = viewModelScope.launch(start = start) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { message(error.message?.take(160) ?: "This action could not be completed") }
    }
    private fun privilegedTask(block: suspend () -> Unit): Job? {
        if (!adult() || switchingProfile) return null
        val job = task(start = CoroutineStart.LAZY) {
            check(adult() && !switchingProfile) { "Choose an adult profile first" }
            block()
        }
        privilegedJobs.add(job)
        job.invokeOnCompletion { privilegedJobs.remove(job) }
        job.start()
        return job
    }
    private fun haTask(block: suspend () -> Unit) {
        if (state.value.haBusy) return
        privilegedTask {
            mutable.update { it.copy(haBusy = true) }
            try { block() } finally { mutable.update { it.copy(haBusy = false) } }
        }
    }
    private fun Set<String>.takeLastBounded(limit: Int) = toList().takeLast(limit).toSet()

    override fun onCleared() {
        weather.close()
        super.onCleared()
    }
}

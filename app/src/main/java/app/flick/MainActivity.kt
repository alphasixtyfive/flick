package app.flick

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.flick.home.FlickScreen
import app.flick.home.HomeActions
import app.flick.profiles.Profile
import app.flick.themes.ThemeCodec
import app.flick.themes.ThemeDefinition
import app.flick.settings.ProjectLinks
import app.flick.ui.theme.FlickTheme
import app.flick.ui.components.LocalNavigationFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var model: LauncherViewModel
    private val sounds get() = runtime.navigationSounds
    private var photoProfileId: String? = null
    private var choosingProfileWallpaper = false
    private val runtime get() = (application as FlickApplication).runtime
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val continuePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.setContinueAccess(granted)
        if (granted) {
            val before = model.state.value.settings
            model.updateSettings(before, before.copy(showContinue = true))
        }
        if (!granted) model.message("Allow TV listings in Android app permissions to use Continue watching")
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val profile = model.state.value.settings.profiles.firstOrNull { it.id == photoProfileId }
        val wallpaper = choosingProfileWallpaper
        photoProfileId = null
        choosingProfileWallpaper = false
        if (uri != null && profile != null && model.canManage()) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onSuccess {
                    if (wallpaper) model.saveProfileWallpaper(profile, uri.toString())
                    else model.saveProfilePhoto(profile, uri.toString())
                }
                .onFailure { model.message("This photo provider cannot keep the image available. Choose another source.") }
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && model.canManage()) model.selectWallpaperFolder(uri.toString())
    }
    private val wallpaperPicker = activityResultRegistry.register("wallpaper.photos", this, ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && model.canManage()) model.addWallpaperPhotos(uris.map(Uri::toString))
    }
    private var exportingTheme: ThemeDefinition? = null
    private val themePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && model.canManage()) model.importTheme(uri)
    }
    private val themeWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val theme = exportingTheme
        exportingTheme = null
        if (uri != null && theme != null && model.canManage()) model.exportTheme(theme, uri)
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && model.canManage()) model.readBackup(uri)
    }
    private val backupWriter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        model.saveBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[LauncherViewModel::class.java]
        exportingTheme = savedInstanceState?.getString("exportingTheme")?.let { runCatching { ThemeCodec.read(it) }.getOrNull() }
        photoProfileId = savedInstanceState?.getString("photoProfileId")
        choosingProfileWallpaper = savedInstanceState?.getBoolean("choosingProfileWallpaper") ?: false
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val actions = remember(model, sounds) { createActions() }
            val feedback = remember(model, sounds) { { sounds.play(model.state.value.settings.navigationSound) } }
            var previousSound by remember { mutableStateOf(state.settings.navigationSound) }
            LaunchedEffect(state.settings.navigationSound) {
                if (previousSound != state.settings.navigationSound) {
                    previousSound = state.settings.navigationSound
                    sounds.play(state.settings.navigationSound, preview = true)
                }
            }
            CompositionLocalProvider(LocalNavigationFeedback provides feedback) {
                FlickTheme(largeText = state.settings.largeText, appearance = state.settings.appearance) { FlickScreen(state, actions) }
            }
        }
        readSetupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) model.requestHome()
        readSetupIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exportingTheme?.let { outState.putString("exportingTheme", ThemeCodec.write(it)) }
        photoProfileId?.let { outState.putString("photoProfileId", it) }
        outState.putBoolean("choosingProfileWallpaper", choosingProfileWallpaper)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) {
            model.setContinueAccess(checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED)
            model.setActive(true)
            runtime.resume()
            runtime.updates.refresh()
        }
    }
    override fun onPause() { if (::model.isInitialized) { model.setActive(false); runtime.pause() }; super.onPause() }

    private fun openAndroid(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { model.message("This Android screen is unavailable") }
    }

    private fun createActions() = HomeActions(
        launchApp = model::launchApp,
        openContinue = model::openContinue,
        updateSettings = model::updateSettings,
        appInfo = { app ->
            if (model.canManage()) openAndroid(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        },
        uninstallApp = { app ->
            if (model.canUninstall(app)) openAndroid(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
        },
        removeContinue = model::removeContinue,
        openProjectLink = { url ->
            if (model.canManage() && ProjectLinks.allowed(url))
                openAndroid(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
        chooseProfile = model::chooseProfile,
        cancelProfileSelection = model::cancelProfileSelection,
        chooseProfilePhoto = ::chooseProfilePhoto,
        removeProfilePhoto = { model.saveProfilePhoto(it, null) },
        chooseProfileWallpaper = ::chooseProfileWallpaper,
        removeProfileWallpaper = { model.saveProfileWallpaper(it, null) },
        setProfileWallpaper = model::saveProfileWallpaper,
        searchPlaces = model::searchPlaces,
        rotateWallpaper = model::rotateWallpaper,
        saveWallpaperSettings = model::saveWallpaperSettings,
        chooseWallpaperFolder = {
            if (model.canManage()) runCatching { folderPicker.launch(null) }
                .onFailure { model.message("No folder picker is installed") }
        },
        chooseWallpaperPhotos = {
            if (model.canManage()) runCatching { wallpaperPicker.launch(arrayOf("image/*")) }
                .onFailure { model.message("No photo picker is installed on this TV") }
        },
        setHomePower = model::setHomePower,
        runHomeScene = model::runHomeScene,
        configureHomeAssistant = model::configureHomeAssistant,
        discoverDisplayPlayers = model::discoverDisplayPlayers,
        saveDisplayPowerEntity = model::saveDisplayPowerEntity,
        discoverHomeEntities = model::discoverHomeEntities,
        retryHomeAssistant = model::retryHomeAssistant,
        pairHomeAssistant = {
            if (model.canManage()) {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                model.pairHomeAssistant()
            }
        },
        setIntegrationEnabled = model::setIntegrationEnabled,
        forgetIntegration = model::forgetIntegration,
        setNotificationsEnabled = model::setNotificationsEnabled,
        setLauncherManagement = model::setLauncherManagement,
        requestOverlayPermission = { if (model.canManage()) openAndroid(runtime.notifications.overlayPermissionIntent()) },
        clearProfileError = model::clearProfileError,
        saveHomeControls = model::saveHomeControls,
        openAndroidSettings = { if (model.canManage()) openAndroid(Intent(Settings.ACTION_SETTINGS)) },
        openScreensaverSettings = { if (model.canManage()) openAndroid(Intent(Settings.ACTION_DREAM_SETTINGS)) },
        importTheme = {
            if (model.canManage()) runCatching { themePicker.launch(arrayOf("application/json", "text/plain")) }
                .onFailure { model.message("No file picker is installed") }
        },
        exportTheme = { theme ->
            if (model.canManage()) {
                exportingTheme = theme
                runCatching { themeWriter.launch("Flick.theme.json") }
                    .onFailure { exportingTheme = null; model.message("No file picker is installed") }
            }
        },
        selectTheme = model::selectTheme,
        removeTheme = model::removeTheme,
        prepareBackup = model::prepareBackup,
        saveBackup = {
            if (model.canManage()) runCatching { backupWriter.launch("Flick.flickbackup") }
                .onFailure { model.message("No file picker is installed") }
        },
        chooseBackup = {
            if (model.canManage()) runCatching { backupPicker.launch(arrayOf("*/*")) }
                .onFailure { model.message("No file picker is installed") }
        },
        restoreBackup = model::restoreBackup,
        discardBackup = model::discardBackup,
        dismissMessage = model::dismissMessage,
        saveProfile = model::saveProfile,
        setProfileApp = model::setProfileApp,
        reorderApps = model::reorderApps,
        deleteProfile = model::deleteProfile,
        showMessage = model::message,
        openRecoverySettings = {
            if (model.state.value.recoveryRequired) openAndroid(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        },
        requestContinueAccess = {
            if (model.canManage()) runCatching { continuePermission.launch(READ_TV_LISTINGS) }
                .onFailure { model.message("TV listing access is unavailable on this device") }
        },
        checkUpdates = model::checkUpdates,
        downloadUpdate = model::downloadUpdate,
        cancelUpdate = model::cancelUpdate,
        installUpdate = { if (model.canManage()) runtime.updates.install(onPermissionRequired = ::openAndroid) },
    )

    private fun chooseProfilePhoto(profile: Profile) {
        if (!model.canManage()) return
        photoProfileId = profile.id
        choosingProfileWallpaper = false
        runCatching { photoPicker.launch(arrayOf("image/*")) }.onFailure { model.message("No photo picker is installed") }
    }

    private fun chooseProfileWallpaper(profile: Profile) {
        if (!model.canManage()) return
        photoProfileId = profile.id
        choosingProfileWallpaper = true
        runCatching { photoPicker.launch(arrayOf("image/*")) }.onFailure { model.message("No photo picker is installed") }
    }

    private fun readSetupIntent(intent: Intent?) {
        val setup = intent?.getStringExtra("flick.setup") ?: return
        intent.removeExtra("flick.setup")
        if (model.canManage()) model.readLegacyBackup(setup)
        else model.message("Choose an adult profile before restoring setup")
    }

    private companion object { const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS" }
}

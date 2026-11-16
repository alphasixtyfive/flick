package app.flick

import android.app.AlertDialog
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
import app.flick.settings.SetupImport
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
    private val setupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use(SetupImport::read)
                    ?: error("Setup file could not be read")
            } }.onSuccess(::confirmImport).onFailure { model.message("Setup file could not be read") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[LauncherViewModel::class.java]
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
                FlickTheme(largeText = state.settings.largeText) { FlickScreen(state, actions) }
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
        chooseProfilePhoto = ::chooseProfilePhoto,
        removeProfilePhoto = { model.saveProfilePhoto(it, null) },
        chooseProfileWallpaper = ::chooseProfileWallpaper,
        removeProfileWallpaper = { model.saveProfileWallpaper(it, null) },
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
        importSetup = {
            if (model.canManage()) runCatching { setupPicker.launch(arrayOf("application/json", "text/plain")) }
                .onFailure { model.message("No file picker is installed") }
        },
        dismissMessage = model::dismissMessage,
        saveProfile = model::saveProfile,
        deleteProfile = model::deleteProfile,
        showMessage = model::message,
        openRecoverySettings = {
            if (model.state.value.recoveryRequired) openAndroid(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        },
        requestContinueAccess = {
            if (model.canManage()) runCatching { continuePermission.launch(READ_TV_LISTINGS) }
                .onFailure { model.message("TV listing access is unavailable on this device") }
        },
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
        confirmImport(setup)
    }

    private fun confirmImport(raw: String) {
        if (!model.canManage()) { model.message("Choose an adult profile before importing setup"); return }
        val parsed = runCatching { SetupImport.parse(raw) }.getOrElse { model.message("This setup file is invalid"); return }
        AlertDialog.Builder(this).setTitle("Import Flick setup?")
            .setMessage("Replace settings and ${parsed.settings.profiles.size} profiles? Existing setup stays unchanged until you choose Import.")
            .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val homeAssistant = parsed.homeAssistant
                        model.importSettings(parsed.settings, parsed.wallpaper, homeAssistant?.url, homeAssistant?.token,
                            homeAssistant?.controls.orEmpty())
                        model.message("Setup imported")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { model.message("Setup was not fully imported. Check the settings before retrying.") }
                }
            }.show()
    }

    private companion object { const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS" }
}

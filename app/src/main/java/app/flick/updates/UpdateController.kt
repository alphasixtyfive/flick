package app.flick.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.flick.BuildConfig
import app.flick.settings.ProjectLinks
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class UpdatePhase { Idle, Checking, Current, Available, Downloading, Verifying, Ready, Installing }

data class UpdateState(
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: UpdateRelease? = null,
    val progress: Int = 0,
    val message: String? = null,
)

/** User-initiated updates. The caller supplies the same foreground/adult guard used by Settings. */
class UpdateController(context: Context, private val scope: CoroutineScope, private val canInstall: () -> Boolean) {
    private val app = context.applicationContext
    private val download by lazy { UpdateDownload() }
    private val installer by lazy { UpdateInstaller(app) }
    private val folder = File(app.cacheDir, "updates")
    private val repository = ProjectLinks.SOURCE.removePrefix("https://github.com/")
    private val generation = AtomicLong()
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var readyFile: File? = null
    private var activeSession: PendingUpdate? = null

    fun check() {
        if (!canInstall() || busy()) return
        val epoch = generation.incrementAndGet()
        start(epoch) {
            deleteReadyFile()
            mutableState.value = UpdateState(phase = UpdatePhase.Checking)
            val release = withTimeout(30_000) {
                val bytes = download.metadata(UpdatePolicy.metadataUrl(repository))
                withContext(Dispatchers.Default) {
                    try { UpdatePolicy.release(JSONObject(bytes.toString(Charsets.UTF_8)), repository) }
                    catch (_: Exception) { throw UpdateException("This release is missing valid update information. Check the project page.") }
                }
            }
            if (UpdatePolicy.newer(release.version, BuildConfig.VERSION_NAME)) {
                mutableState.value = UpdateState(phase = UpdatePhase.Available, release = release)
            } else mutableState.value = UpdateState(phase = UpdatePhase.Current)
        }
    }

    fun download() {
        if (!canInstall() || busy()) return
        val release = state.value.release ?: return
        val epoch = generation.incrementAndGet()
        start(epoch) {
            deleteReadyFile()
            mutableState.update { it.copy(phase = UpdatePhase.Downloading, progress = 0, message = null) }
            var created: File? = null
            var retained = false
            try {
                val file = withContext(Dispatchers.IO) {
                    if (!folder.isDirectory && !folder.mkdirs()) throw UpdateException("The update folder could not be created.")
                    File.createTempFile("flick-", ".apk", folder).also { created = it }
                }
                currentCoroutineContext().ensureActive()
                withTimeout(180_000) {
                    val digest = download.apk(release, file) { percent ->
                        if (generation.get() == epoch) mutableState.update {
                            if (it.phase == UpdatePhase.Downloading) it.copy(progress = percent) else it
                        }
                    }
                    if (digest != release.sha256) throw UpdateException("The update download could not be verified. Try again.")
                    mutableState.update { it.copy(phase = UpdatePhase.Verifying) }
                    installer.verify(file, release)
                }
                currentCoroutineContext().ensureActive()
                if (!canInstall()) throw UpdateException("Choose an adult profile and open Updates to continue.")
                readyFile = file
                retained = true
                mutableState.update { it.copy(phase = UpdatePhase.Ready, progress = 100) }
            } finally {
                if (!retained) withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            }
        }
    }

    /** Permission return never installs automatically; the viewer selects Install update again. */
    fun install(onPermissionRequired: (Intent) -> Unit) {
        if (!canInstall() || busy() || state.value.phase != UpdatePhase.Ready) return
        val release = state.value.release ?: return
        val file = readyFile ?: return
        if (!installer.allowed()) {
            mutableState.update { it.copy(message = "Allow Flick to install updates, then select Install update again.") }
            try { onPermissionRequired(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))) }
            catch (_: Exception) { mutableState.update {
                it.copy(message = "Open Android Settings and allow Flick under Install unknown apps, then return to Updates.")
            } }
            return
        }
        val epoch = generation.incrementAndGet()
        start(epoch) {
            mutableState.update { it.copy(phase = UpdatePhase.Verifying, message = null) }
            val versionCode = try { installer.verify(file, release) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { deleteReadyFile(); throw failure }
            if (!canInstall()) throw UpdateException("Choose an adult profile and open Updates to continue.")
            var pending: PendingUpdate? = null
            try {
                pending = installer.stage(file, versionCode)
                activeSession = pending
                currentCoroutineContext().ensureActive()
                if (!canInstall()) throw UpdateException("Choose an adult profile and open Updates to continue.")
                mutableState.update { it.copy(phase = UpdatePhase.Installing) }
                installer.commit(pending)
                deleteReadyFile()
            } catch (failure: Exception) {
                withContext(NonCancellable) { installer.abandon(pending ?: installer.pending()) }
                activeSession = null
                throw failure
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
        activeSession = null
        val previous = state.value
        mutableState.value = previous.copy(
            phase = if (previous.release == null) UpdatePhase.Idle else UpdatePhase.Available,
            progress = 0, message = null,
        )
        if (cleanup?.isCompleted == false) return
        val previousOperation = operation
        previousOperation?.cancel()
        val oldFile = readyFile
        readyFile = null
        cleanup = scope.launch {
            try {
                previousOperation?.cancelAndJoin()
                withContext(Dispatchers.IO) { oldFile?.delete() }
                installer.abandon()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.Installing,
                    message = "Android could not cancel the previous update. Try Cancel installation again.") }
            }
        }
    }

    /** Reconcile Android's session after permission screens or process recreation, without installing. */
    fun refresh() {
        if (operation?.isCompleted == false || cleanup?.isCompleted == false) return
        val epoch = generation.get()
        start(epoch) {
            withContext(Dispatchers.IO) {
                folder.listFiles()?.filter { it != readyFile && it.name.startsWith("flick-") && it.name.endsWith(".apk") }
                    ?.forEach { it.delete() }
            }
            val pending = withContext(Dispatchers.IO) { installer.pending() }
            if (pending != null) {
                val finished = installer.finished()
                val committed = if (finished) false else installer.committed()
                if (generation.get() != epoch) return@start
                when {
                    finished -> {
                        if (!installer.clear(pending)) return@start
                        activeSession = null
                        deleteReadyFile()
                        mutableState.value = UpdateState(phase = UpdatePhase.Current)
                    }
                    committed -> {
                        activeSession = pending
                        mutableState.update { it.copy(phase = UpdatePhase.Installing) }
                    }
                    else -> {
                        if (!installer.abandon(pending) || generation.get() != epoch) return@start
                        activeSession = null
                        mutableState.update { it.copy(phase = if (it.release == null) UpdatePhase.Idle else UpdatePhase.Available,
                            message = "Installation was not completed. You can try again.") }
                    }
                }
            } else if (state.value.phase == UpdatePhase.Ready && installer.allowed()) {
                mutableState.update { it.copy(message = null) }
            }
        }
    }

    fun onInstallResult(intent: Intent): Job {
        val epoch = generation.get()
        return scope.launch {
            try {
                val pending = withContext(Dispatchers.IO) { installer.pending() } ?: return@launch
                if (generation.get() != epoch || cleanup?.isCompleted == false || !installer.matches(intent, pending)) return@launch
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (activeSession != pending || state.value.phase != UpdatePhase.Installing) return@launch
                        if (confirmation == null || !canInstall()) {
                            installer.abandon(pending)
                            if (generation.get() != epoch) return@launch
                            activeSession = null
                            installFailed("Open Updates from an adult profile to try again.")
                            return@launch
                        }
                        try { app.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) {
                            installer.abandon(pending)
                            if (generation.get() != epoch) return@launch
                            activeSession = null
                            installFailed("Android could not open the installer. Try again.")
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        if (!installer.clear(pending)) return@launch
                        activeSession = null
                        mutableState.value = UpdateState(phase = UpdatePhase.Current)
                    }
                    else -> {
                        if (!installer.clear(pending)) return@launch
                        activeSession = null
                        val message = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled. You can try again."
                            PackageInstaller.STATUS_FAILURE_STORAGE -> "Android needs more free space to install this update."
                            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android could not update this installation. Keep the existing app and check its update source."
                            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update is not compatible with this Android device."
                            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the update. Check install permissions and try again."
                            else -> "Android could not install the update. You can try again."
                        }
                        installFailed(message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation.get() == epoch) installFailed("Android could not complete the update. Open Updates to try again.")
            }
        }
    }

    private fun installFailed(message: String) {
        mutableState.update { it.copy(phase = if (it.release == null) UpdatePhase.Idle else UpdatePhase.Available, message = message) }
    }

    private fun busy(): Boolean = operation?.isCompleted == false || cleanup?.isCompleted == false || state.value.phase == UpdatePhase.Installing

    private fun start(epoch: Long, action: suspend () -> Unit) {
        operation = scope.launch {
            try { action() }
            catch (_: TimeoutCancellationException) {
                if (generation.get() == epoch) installFailed("The update request timed out. Check the connection and try again.")
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation.get() == epoch) mutableState.update {
                    it.copy(phase = if (readyFile != null) UpdatePhase.Ready else if (it.release == null) UpdatePhase.Idle else UpdatePhase.Available,
                        message = (failure as? UpdateException)?.message ?: "The update could not be completed. Try again.")
                }
            }
        }
    }

    private suspend fun deleteReadyFile() {
        val file = readyFile
        readyFile = null
        withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
    }
}

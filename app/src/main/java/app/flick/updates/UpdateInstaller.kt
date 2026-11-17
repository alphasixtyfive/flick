package app.flick.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.flick.FlickApplication
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PendingUpdate(val sessionId: Int, val token: String, val versionCode: Long)

internal class UpdateInstaller(private val context: Context) {
    private val manager = context.packageManager
    private val installer = manager.packageInstaller
    private val preferences by lazy { context.getSharedPreferences("update-install", Context.MODE_PRIVATE) }

    fun allowed(): Boolean = manager.canRequestPackageInstalls()

    suspend fun verify(file: File, release: UpdateRelease): Long = withContext(Dispatchers.IO) {
        if (file.length() != release.size) throw UpdateException("The downloaded update is incomplete. Download it again.")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.sha256)
            throw UpdateException("The downloaded update could not be verified. Download it again.")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val mine = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.path, flags)
            ?: throw UpdateException("Android could not read the update package.")
        if (candidate.packageName != context.packageName || candidate.versionName != release.version)
            throw UpdateException("This download is not the expected Flick release.")
        if (versionCode(candidate) <= versionCode(mine)) throw UpdateException("This update is not newer than the installed version.")
        if ((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT)
            throw UpdateException("This update requires a newer version of Android.")
        if (!compatibleSigners(mine, candidate)) throw UpdateException("This update uses a different signing key. Keep the installed app and use its original update source.")
        versionCode(candidate)
    }

    suspend fun stage(file: File, versionCode: Long): PendingUpdate = withContext(Dispatchers.IO) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        var retained = false
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val pending = PendingUpdate(id, UUID.randomUUID().toString(), versionCode)
            if (!preferences.edit().putInt("session", id).putString("token", pending.token)
                    .putLong("version", versionCode).commit()) throw UpdateException("The update session could not be saved.")
            retained = true
            pending
        } finally {
            if (!retained) withContext(NonCancellable) { abandonSession(id) }
        }
    }

    suspend fun commit(pending: PendingUpdate) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (pending() != pending) throw UpdateException("The update session has ended. Try again.")
        val result = Intent(context, UpdateResultReceiver::class.java).setAction(RESULT_ACTION)
            .putExtra(RESULT_TOKEN, pending.token)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, pending.sessionId, result, flags)
        installer.openSession(pending.sessionId).use { it.commit(callback.intentSender) }
    }

    fun pending(): PendingUpdate? {
        val id = preferences.getInt("session", -1)
        val token = preferences.getString("token", null)
        val version = preferences.getLong("version", 0)
        return if (id >= 0 && !token.isNullOrEmpty() && version > 0) PendingUpdate(id, token, version) else null
    }

    fun matches(intent: Intent, pending: PendingUpdate): Boolean = intent.action == RESULT_ACTION &&
        intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == pending.sessionId &&
        intent.getStringExtra(RESULT_TOKEN) == pending.token

    suspend fun finished(): Boolean = withContext(Dispatchers.IO) {
        val pending = pending() ?: return@withContext false
        versionCode(manager.getPackageInfo(context.packageName, 0)) >= pending.versionCode
    }

    suspend fun committed(): Boolean = withContext(Dispatchers.IO) {
        pending()?.let { installer.getSessionInfo(it.sessionId)?.isSealed == true } ?: false
    }

    suspend fun abandon(expected: PendingUpdate? = pending()) = withContext(Dispatchers.IO) {
        if (expected != null && pending() == expected) {
            abandonSession(expected.sessionId)
            clear(expected)
        } else false
    }

    fun clear(expected: PendingUpdate): Boolean {
        if (pending() != expected) return false
        preferences.edit().clear().apply()
        return true
    }

    private fun abandonSession(id: Int) {
        try { installer.abandonSession(id) }
        catch (_: SecurityException) { /* Android may already have completed the session. */ }
        catch (_: IllegalArgumentException) { /* A completed session no longer exists. */ }
    }

    private fun compatibleSigners(installed: PackageInfo, candidate: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val mine = installed.signingInfo ?: return false
            val theirs = candidate.signingInfo ?: return false
            return UpdatePolicy.acceptsSigners(
                mine.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet(),
                theirs.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet(),
                theirs.signingCertificateHistory.orEmpty().map { it.toCharsString() }.toSet(),
                mine.hasMultipleSigners(), theirs.hasMultipleSigners(),
            )
        }
        @Suppress("DEPRECATION")
        val mine = installed.signatures.orEmpty().map { it.toCharsString() }.toSet()
        @Suppress("DEPRECATION")
        val theirs = candidate.signatures.orEmpty().map { it.toCharsString() }.toSet()
        return mine.isNotEmpty() && mine == theirs
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    companion object {
        private const val RESULT_ACTION = "app.flick.UPDATE_RESULT"
        private const val RESULT_TOKEN = "update-token"
    }
}

/** The installer can reach this private receiver only through our explicit PendingIntent. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        try {
            (context.applicationContext as FlickApplication).runtime.updates.onInstallResult(intent)
                .invokeOnCompletion { result.finish() }
        } catch (failure: Exception) {
            result.finish()
            throw failure
        }
    }
}

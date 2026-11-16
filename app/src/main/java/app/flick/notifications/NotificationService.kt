package app.flick.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.flick.FlickApplication
import app.flick.MainActivity
import app.flick.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** User-enabled connection; Android may restore it after killing the process. */
class NotificationService : Service() {
    private lateinit var controller: NotificationController
    private var backgroundEnabled = false
    private var started = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recovery: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, "Home Assistant connection", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as FlickApplication).runtime
        controller = runtime.notifications
        try {
            if (intent == null) {
                // Meet Android's foreground deadline before waiting for private settings.
                val generation = controller.recoverBackgroundRequest() ?: run { stopSelf(); return START_NOT_STICKY }
                backgroundEnabled = true
                promote()
                if (!started) {
                    started = true
                    controller.serviceStarted(this)
                    runtime.onBackgroundServiceStarted()
                }
                recovery?.cancel()
                recovery = serviceScope.launch {
                    try {
                        if (!runtime.bridge.recoveryAllowed() && controller.isCurrentRecovery(generation)) {
                            controller.enableBackground(false)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (controller.isCurrentRecovery(generation)) controller.enableBackground(false)
                    }
                }
                return START_STICKY
            }
            when (intent.action) {
                StartBackground -> {
                    recovery?.cancel()
                    recovery = null
                    backgroundEnabled = controller.backgroundRequested
                    if (!backgroundEnabled) { stopSelf(); return START_NOT_STICKY }
                    promote()
                }
                else -> { stopSelf(); return START_NOT_STICKY }
            }
            if (!started) {
                started = true
                controller.serviceStarted(this)
                runtime.onBackgroundServiceStarted()
            }
        } catch (_: RuntimeException) {
            controller.serviceFailed()
            shutdown()
        }
        return restartPolicy()
    }

    fun shutdown() {
        recovery?.cancel()
        recovery = null
        backgroundEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restartPolicy() = if (backgroundEnabled) START_STICKY else START_NOT_STICKY

    private fun promote() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, Channel)
            .setSmallIcon(R.drawable.flick_notification)
            .setContentTitle("Flick")
            .setContentText("Home Assistant connection is enabled")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            startForeground(NotificationId, notification, types)
        } else startForeground(NotificationId, notification)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (::controller.isInitialized) controller.serviceStopped(this)
        if (started) (application as FlickApplication).runtime.onBackgroundServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val StartBackground = "app.flick.notifications.START"
        private const val Channel = "flick_home_assistant"
        private const val NotificationId = 71
    }
}

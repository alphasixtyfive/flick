package app.flick.tv.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.MainThread
import app.flick.tv.R
import app.flick.tv.settings.NavigationSound

@MainThread
class NavigationSounds(context: Context) {
    private val app = context.applicationContext
    private val playback = NavigationSoundPlayback(
        createSoft = { AndroidSoftSound(app) },
        playSystem = { app.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEY_CLICK) },
        now = SystemClock::elapsedRealtime,
    )

    fun play(mode: NavigationSound, preview: Boolean = false) = playback.play(mode, preview)
    fun close() = playback.close()
}

private class AndroidSoftSound(private val context: Context) : SoftSoundBackend {
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var tick = 0

    override fun load(onComplete: (Boolean) -> Unit) {
        pool.setOnLoadCompleteListener { _, _, status -> onComplete(status == 0) }
        tick = pool.load(context, R.raw.navigation_tick, 1)
        if (tick == 0) onComplete(false)
    }

    override fun play(): Int = pool.play(tick, .3f, .3f, 0, 0, 1f)
    override fun stop(stream: Int) { pool.stop(stream) }
    override fun close() {
        try { pool.setOnLoadCompleteListener(null) } finally { pool.release() }
    }
}

internal class NavigationSoundGate {
    private var last: Long? = null
    fun accept(now: Long, preview: Boolean = false): Boolean {
        if (!preview && last?.let { now - it < 65 } == true) return false
        last = now
        return true
    }
}

internal fun isNavigationFeedback(action: Int, keyCode: Int, repeatCount: Int, longPress: Boolean): Boolean =
    action == KeyEvent.ACTION_DOWN && repeatCount == 0 && !longPress && keyCode in feedbackKeys

private val feedbackKeys = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT,
)

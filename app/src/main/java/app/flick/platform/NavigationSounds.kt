package app.flick.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import app.flick.R
import app.flick.settings.NavigationSound

class NavigationSounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var ready = false
    private var closed = false
    private var pendingPreview = false
    private var activeStream = 0
    private val gate = NavigationSoundGate()
    private val tick: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status ->
            if (!closed) {
                ready = status == 0
                if (ready && pendingPreview) { pendingPreview = false; play(NavigationSound.Soft, preview = true) }
            }
        }
        tick = pool.load(context, R.raw.navigation_tick, 1)
    }

    fun play(mode: NavigationSound, preview: Boolean = false) {
        if (closed) return
        if (mode == NavigationSound.Off) {
            pendingPreview = false
            stopSoft()
            return
        }
        if (mode == NavigationSound.Soft && !ready) {
            if (preview) pendingPreview = true
            return
        }
        pendingPreview = false
        if (!gate.accept(SystemClock.elapsedRealtime(), preview)) return
        when (mode) {
            NavigationSound.Soft -> activeStream = pool.play(tick, .3f, .3f, 0, 0, 1f)
            NavigationSound.System -> {
                stopSoft()
                audio.playSoundEffect(AudioManager.FX_KEY_CLICK)
            }
            NavigationSound.Off -> Unit
        }
    }

    private fun stopSoft() {
        if (activeStream != 0) pool.stop(activeStream)
        activeStream = 0
    }

    fun close() {
        if (closed) return
        closed = true
        pendingPreview = false
        pool.setOnLoadCompleteListener(null)
        pool.release()
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

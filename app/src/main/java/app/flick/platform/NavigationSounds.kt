package app.flick.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import app.flick.R
import app.flick.settings.NavigationSound

class NavigationSounds(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var ready = false
    private var last = 0L
    private val tick: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        tick = pool.load(context, R.raw.navigation_tick, 1)
    }

    fun play(mode: NavigationSound) {
        val now = SystemClock.elapsedRealtime()
        if (now - last < 65 || mode == NavigationSound.Off) return
        last = now
        when (mode) {
            NavigationSound.Soft -> if (ready) pool.play(tick, .18f, .18f, 0, 0, 1f)
            NavigationSound.System -> audio.playSoundEffect(AudioManager.FX_KEY_CLICK)
            NavigationSound.Off -> Unit
        }
    }

    fun close() = pool.release()
}

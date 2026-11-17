package app.flick.platform

import app.flick.settings.NavigationSound

internal interface SoftSoundBackend {
    fun load(onComplete: (Boolean) -> Unit)
    fun play(): Int
    fun stop(stream: Int)
    fun close()
}

/** Main-thread owner. Optional audio gets one initialization attempt and fails quietly. */
internal class NavigationSoundPlayback(
    private val createSoft: () -> SoftSoundBackend,
    private val playSystem: () -> Unit,
    private val now: () -> Long,
) {
    private var soft: SoftSoundBackend? = null
    private var attempted = false
    private var ready = false
    private var closed = false
    private var pendingPreview = false
    private var activeStream = 0
    private val gate = NavigationSoundGate()

    fun play(mode: NavigationSound, preview: Boolean = false) {
        if (closed) return
        if (mode == NavigationSound.Off) {
            stopSoft()
            return
        }
        if (mode == NavigationSound.Soft) {
            prepareSoft()
            if (!ready) {
                if (preview && soft != null) pendingPreview = true
                return
            }
        }
        pendingPreview = false
        if (!gate.accept(now(), preview)) return
        when (mode) {
            NavigationSound.Soft -> try { activeStream = soft?.play() ?: 0 }
                catch (_: RuntimeException) { releaseSoft() }
            NavigationSound.System -> {
                stopSoft()
                try { playSystem() } catch (_: RuntimeException) { }
            }
            NavigationSound.Off -> Unit
        }
    }

    private fun prepareSoft() {
        if (attempted || closed) return
        attempted = true
        try {
            val backend = createSoft()
            soft = backend
            backend.load { loaded ->
                if (closed || soft !== backend) return@load
                if (!loaded) { releaseSoft(); return@load }
                ready = true
                if (pendingPreview) { pendingPreview = false; play(NavigationSound.Soft, preview = true) }
            }
        } catch (_: RuntimeException) { releaseSoft() }
    }

    private fun stopSoft() {
        pendingPreview = false
        val stream = activeStream
        activeStream = 0
        if (stream != 0) try { soft?.stop(stream) }
            catch (_: RuntimeException) { releaseSoft() }
    }

    fun close() {
        if (closed) return
        closed = true
        releaseSoft()
    }

    private fun releaseSoft() {
        val backend = soft
        soft = null
        ready = false
        pendingPreview = false
        activeStream = 0
        try { backend?.close() } catch (_: RuntimeException) { }
    }
}

package app.flick.platform

import app.flick.settings.NavigationSound
import org.junit.Assert.*
import org.junit.Test

class NavigationSoundPlaybackTest {
    @Test fun offAndSystemDoNotInitializeSoftAudio() {
        var created = 0
        var clicks = 0
        val sounds = NavigationSoundPlayback({ created++; FakeBackend() }, { clicks++ }, { 0 })
        sounds.play(NavigationSound.Off)
        sounds.play(NavigationSound.System)
        sounds.close()
        assertEquals(0, created)
        assertEquals(1, clicks)
    }

    @Test fun loadingDoesNotReplayOldNavigationButPreviewPlaysOnceReady() {
        val backend = FakeBackend()
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft)
        backend.complete(true)
        assertEquals(0, backend.plays)
        sounds.play(NavigationSound.Soft)
        assertEquals(1, backend.plays)

        val previewBackend = FakeBackend()
        val preview = NavigationSoundPlayback({ previewBackend }, {}, { 0 })
        preview.play(NavigationSound.Soft, preview = true)
        previewBackend.complete(true)
        previewBackend.complete(true)
        assertEquals(1, previewBackend.plays)
    }

    @Test fun anImmediatelyLoadedPreviewDoesNotPlayTwice() {
        val backend = FakeBackend(immediate = true)
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft, preview = true)
        assertEquals(1, backend.plays)
    }

    @Test fun switchingOffDiscardsAPendingPreview() {
        val backend = FakeBackend()
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft, preview = true)
        sounds.play(NavigationSound.Off)
        backend.complete(true)
        assertEquals(0, backend.plays)
    }

    @Test fun switchingToSystemDuringLoadDiscardsTheSoftPreview() {
        val backend = FakeBackend()
        var systemClicks = 0
        val sounds = NavigationSoundPlayback({ backend }, { systemClicks++ }, { 0 })
        sounds.play(NavigationSound.Soft, preview = true)
        sounds.play(NavigationSound.System, preview = true)
        backend.complete(true)
        assertEquals(1, systemClicks)
        assertEquals(0, backend.plays)
    }

    @Test fun nativeInitializationFailureIsTriedOnlyOnceAndSystemStillWorks() {
        var attempts = 0
        var clicks = 0
        val sounds = NavigationSoundPlayback({ attempts++; error("Native setup failed") }, { clicks++ }, { 0 })
        sounds.play(NavigationSound.Soft)
        sounds.play(NavigationSound.Soft, preview = true)
        sounds.play(NavigationSound.System)
        assertEquals(1, attempts)
        assertEquals(1, clicks)
    }

    @Test fun loadFailureReleasesTheBackendAndIgnoresLateSuccess() {
        val backend = FakeBackend()
        var attempts = 0
        val sounds = NavigationSoundPlayback({ attempts++; backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft, preview = true)
        backend.complete(false)
        backend.complete(true)
        sounds.play(NavigationSound.Soft)
        assertEquals(1, attempts)
        assertEquals(1, backend.closes)
        assertEquals(0, backend.plays)
    }

    @Test fun aThrowingLoadStillReleasesTheConstructedBackend() {
        val backend = FakeBackend(failLoad = true)
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft)
        sounds.close()
        assertEquals(1, backend.closes)
    }

    @Test fun playbackFailureDoesNotEscapeOrReinitializeTheBackend() {
        val backend = FakeBackend(immediate = true, failPlay = true)
        var attempts = 0
        val sounds = NavigationSoundPlayback({ attempts++; backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft)
        sounds.play(NavigationSound.Soft, preview = true)
        assertEquals(1, attempts)
        assertEquals(1, backend.closes)
    }

    @Test fun closeIsIdempotentAndLateLoadDoesNotReviveSound() {
        val backend = FakeBackend()
        val sounds = NavigationSoundPlayback({ backend }, { error("Closed sounds played") }, { 0 })
        sounds.play(NavigationSound.Soft, preview = true)
        sounds.close()
        sounds.close()
        backend.complete(true)
        sounds.play(NavigationSound.Soft)
        sounds.play(NavigationSound.System)
        assertEquals(1, backend.closes)
        assertEquals(0, backend.plays)
    }

    @Test fun stoppingAFailedNativeStreamReleasesItWithoutEscaping() {
        val backend = FakeBackend(immediate = true, failStop = true)
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft)
        sounds.play(NavigationSound.Off)
        sounds.close()
        assertEquals(1, backend.closes)
    }

    @Test fun releaseFailureStillLeavesThePlayerClosed() {
        val backend = FakeBackend(failClose = true)
        val sounds = NavigationSoundPlayback({ backend }, {}, { 0 })
        sounds.play(NavigationSound.Soft)
        sounds.close()
        sounds.close()
        backend.complete(true)
        sounds.play(NavigationSound.Soft, preview = true)
        assertEquals(1, backend.closes)
        assertEquals(0, backend.plays)
    }

    @Test fun systemAudioFailureDoesNotFallBackToCreatingASoftPlayer() {
        var attempts = 0
        var created = 0
        val sounds = NavigationSoundPlayback({ created++; FakeBackend() }, { attempts++; error("Audio service unavailable") }, { 0 })
        sounds.play(NavigationSound.System)
        sounds.close()
        assertEquals(1, attempts)
        assertEquals(0, created)
    }

    private class FakeBackend(
        private val immediate: Boolean = false,
        private val failLoad: Boolean = false,
        private val failPlay: Boolean = false,
        private val failStop: Boolean = false,
        private val failClose: Boolean = false,
    ) : SoftSoundBackend {
        private var onComplete: (Boolean) -> Unit = {}
        var plays = 0
        var closes = 0
        override fun load(onComplete: (Boolean) -> Unit) {
            this.onComplete = onComplete
            if (failLoad) error("Load failed")
            if (immediate) onComplete(true)
        }
        fun complete(loaded: Boolean) { onComplete(loaded) }
        override fun play(): Int { if (failPlay) error("Playback failed"); plays++; return 1 }
        override fun stop(stream: Int) { if (failStop) error("Stop failed") }
        override fun close() { closes++; if (failClose) error("Release failed") }
    }
}

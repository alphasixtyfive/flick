package app.flick.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaCameraTest {
    private val server = "https://ha.example.test:8443"
    private val snapshot = "/api/camera_proxy/camera.entry?authSig=test-signature&width=1280"

    @Test fun linksTheActionToTheSignedSnapshotCamera() {
        val expected = HaCameraSnapshot("camera.entry", server + snapshot)
        assertEquals(expected, HaCamera.snapshot(server, snapshot))
        assertEquals(expected, HaCamera.snapshot(server, server + snapshot))
    }

    @Test fun keepsTheConfiguredBasePathAndTheSignatureOnRelativeSnapshots() {
        val base = "$server/ha"
        val expected = HaCameraSnapshot("camera.entry", base + snapshot)
        assertEquals(expected, HaCamera.snapshot(base, snapshot))
        assertEquals(expected, HaCamera.snapshot(base, base + snapshot))
    }

    @Test fun ignoresExternalUnsignedAndNonCameraImages() {
        listOf("https://example.test$snapshot", "http://ha.example.test:8443$snapshot",
            "https://ha.example.test$snapshot", "/api/camera_proxy/camera.entry", "/local/entry.jpg?authSig=test-signature",
            "/api/camera_proxy/switch.entry?authSig=test-signature").forEach {
            assertNull(it, HaCamera.snapshot(server, it))
        }
    }

    @Test fun allowsOnlyHomeAssistantPlaylistPaths() {
        val path = "/api/hls/0123456789abcdef/master_playlist.m3u8"
        assertEquals(server + path, HaCamera.streamUrl(server, path))
        assertEquals("$server/ha$path", HaCamera.streamUrl("$server/ha", path))
        listOf("https://example.test$path", "//$path", "/local/master_playlist.m3u8", "$path?redirect=1").forEach {
            assertNull(it, HaCamera.streamUrl(server, it))
        }
    }
}

package app.flick.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class HaConnectionHealthTest {
    @Test fun savedCredentialsDoNotProveAConnection() {
        assertEquals(HaConnectionState.Connecting, HaConnectionHealth().state)
        assertEquals(HaConnectionState.Offline, HaConnectionHealth().httpResult(false).state)
    }

    @Test fun authenticatedHttpKeepsControlsOnlineWhileEventsReconnect() {
        val health = HaConnectionHealth().httpResult(true).eventsResult(false)
        assertEquals(HaConnectionState.Connected, health.state)
    }

    @Test fun liveEventsCanKeepConnectionAvailableWhenHttpHasANetworkFailure() {
        val health = HaConnectionHealth().eventsResult(true).httpResult(false)
        assertEquals(HaConnectionState.Connected, health.state)
    }

    @Test fun losingBothTransportsReportsOffline() {
        val health = HaConnectionHealth().httpResult(true).eventsResult(true)
            .eventsResult(false).httpResult(false)
        assertEquals(HaConnectionState.Offline, health.state)
    }

    @Test fun rejectedAuthenticationOverridesAStillOpenEventSocket() {
        val health = HaConnectionHealth().eventsResult(true).httpResult(false, rejected = true)
        assertEquals(HaConnectionState.Offline, health.state)
        assertEquals(HaConnectionState.Offline, health.eventsResult(false).httpResult(false).state)
    }

    @Test fun onlyAuthenticatedSuccessClearsRejectedSignIn() {
        val rejected = HaConnectionHealth().httpResult(false, rejected = true)
        assertEquals(HaConnectionState.Connected, rejected.httpResult(true).state)
        assertEquals(HaConnectionState.Connected, rejected.eventsResult(true).state)
    }
}

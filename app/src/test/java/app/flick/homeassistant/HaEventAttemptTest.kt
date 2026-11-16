package app.flick.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HaEventAttemptTest {
    @Test fun aLateOldSocketEventCannotOverwriteTheReconnectedState() {
        val old = HaEventAttempt()
        var state = "off"
        old.close()
        val replacement = HaEventAttempt()
        replacement.accept { state = "on" }
        old.accept { state = "off" }
        assertEquals("on", state)
    }

    @Test(timeout = 10_000) fun invalidationWaitsForAnInFlightCallbackAndRejectsLaterCallbacks() {
        val attempt = HaEventAttempt()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val published = mutableListOf<String>()
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery = threads.submit {
                attempt.accept {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    published += "current"
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val closing = threads.submit {
                closeStarted.countDown()
                attempt.close()
                closeReturned.countDown()
            }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            delivery.get(2, TimeUnit.SECONDS)
            closing.get(2, TimeUnit.SECONDS)
            attempt.accept { published += "stale" }
            assertEquals(listOf("current"), published)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }
}

package app.flick.weather

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherRefreshTest {
    @Test fun fetchesImmediatelyAndRefreshesSuccessfulReadingsEveryTenMinutes() = runTest {
        val publishedAt = mutableListOf<Long>()
        val job = launch { refreshWeather({ WeatherReading(8, 0) }, { publishedAt += currentTime }) }
        runCurrent()
        assertEquals(listOf(0L), publishedAt)
        advanceTimeBy(599_999)
        runCurrent()
        assertEquals(listOf(0L), publishedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 600_000L), publishedAt)
        job.cancel()
    }

    @Test fun failedRequestsBackOffFromThirtySecondsToATenMinuteLimit() = runTest {
        val requestedAt = mutableListOf<Long>()
        val job = launch {
            refreshWeather({ requestedAt += currentTime; throw IOException() }, { error("No successful reading") })
        }
        runCurrent()
        for (interval in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L)) {
            advanceTimeBy(interval)
            runCurrent()
        }
        assertEquals(listOf(0L, 30_000L, 90_000L, 210_000L, 450_000L, 930_000L, 1_530_000L, 2_130_000L), requestedAt)
        job.cancel()
    }

    @Test fun successResetsBackoffAndFailuresDoNotPublishAnEmptyReading() = runTest {
        var requests = 0
        val publishedAt = mutableListOf<Long>()
        val job = launch {
            refreshWeather({
                requests++
                if (requests in setOf(1, 2, 4)) throw IOException()
                WeatherReading(8, 0)
            }, { publishedAt += currentTime })
        }
        runCurrent()
        for (interval in listOf(30_000L, 60_000L, 600_000L, 30_000L)) {
            advanceTimeBy(interval)
            runCurrent()
        }
        assertEquals(5, requests)
        assertEquals(listOf(90_000L, 720_000L), publishedAt)
        job.cancel()
    }

    @Test fun cancellingDuringARequestStopsRefreshWithoutPublishing() = runTest {
        val response = CompletableDeferred<WeatherReading>()
        var requests = 0
        val published = mutableListOf<WeatherReading>()
        val job = launch { refreshWeather({ requests++; response.await() }, { published += it }) }
        runCurrent()
        job.cancel()
        response.complete(WeatherReading(8, 0))
        advanceTimeBy(1_000_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, requests)
        assertTrue(published.isEmpty())
    }

    @Test fun cancellationFromTheLoaderIsNotRetried() = runTest {
        var requests = 0
        val job = launch {
            refreshWeather({ requests++; throw CancellationException() }, { error("No successful reading") })
        }
        runCurrent()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, requests)
    }

    @Test fun cancellingDuringBackoffPreventsAnotherRequest() = runTest {
        var requests = 0
        val job = launch {
            refreshWeather({ requests++; throw IOException() }, { error("No successful reading") })
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(1, requests)
    }
}

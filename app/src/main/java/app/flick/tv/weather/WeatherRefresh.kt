package app.flick.tv.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private const val RefreshIntervalMillis = 600_000L
private const val FirstRetryMillis = 30_000L

/** Retry temporary failures sooner without replacing the last successful reading. */
internal suspend fun refreshWeather(
    load: suspend () -> WeatherReading,
    onReading: (WeatherReading) -> Unit,
) {
    var retryMillis = FirstRetryMillis
    while (true) {
        val reading = try {
            load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delay(retryMillis)
            retryMillis = (retryMillis * 2).coerceAtMost(RefreshIntervalMillis)
            continue
        }
        currentCoroutineContext().ensureActive()
        onReading(reading)
        retryMillis = FirstRetryMillis
        delay(RefreshIntervalMillis)
    }
}

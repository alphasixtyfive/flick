package app.flick.tv.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class WeatherRepositoryTest {
    @Test fun closedRepositoryRejectsBothQueriesAndConditions() {
        val weather = WeatherRepository()
        weather.close()
        weather.close()
        assertThrows(IllegalStateException::class.java) { runBlocking { weather.search("Paris") } }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { weather.conditions(WeatherLocation(0.0, 0.0, "Test")) }
        }
    }
}

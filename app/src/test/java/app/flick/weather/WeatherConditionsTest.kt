package app.flick.weather

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WeatherConditionsTest {
    @Test fun clearAndCloudyConditionsStayDistinct() {
        assertCodes(WeatherCondition.Clear, 0, 1)
        assertCodes(WeatherCondition.PartlyCloudy, 2)
        assertCodes(WeatherCondition.Overcast, 3)
    }

    @Test fun fogAndRimeFogAreNotCloudCover() = assertCodes(WeatherCondition.Fog, 45, 48)

    @Test fun rainIncludesShowersAndFreezingRain() {
        assertCodes(WeatherCondition.Drizzle, 51, 53, 55, 56, 57)
        assertCodes(WeatherCondition.Rain, 61, 63, 65, 66, 67, 80, 81, 82)
    }

    @Test fun snowIncludesGrainsAndShowers() = assertCodes(WeatherCondition.Snow, 71, 73, 75, 77, 85, 86)

    @Test fun stormsIncludeHailAndHeavyThunderstorms() = assertCodes(WeatherCondition.Thunderstorm, 95, 96, 97, 99)

    @Test fun unknownCodesDoNotClaimClearWeather() = assertCodes(WeatherCondition.Unknown, -1, 4, 49, 60, 100, Int.MIN_VALUE, Int.MAX_VALUE)

    @Test fun readingKeepsDaylightSeparateFromItsCondition() {
        val day = parseConditions(response(8.6, 0, 1))
        val night = parseConditions(response(-2.6, 0, 0))
        assertEquals(WeatherReading(9, 0, true), day)
        assertEquals(WeatherReading(-3, 0, false), night)
        assertEquals(day.condition, night.condition)
    }

    @Test fun unfamiliarWholeCodesCanStillShowTheTemperature() {
        val reading = parseConditions(response(7.0, 1000, 0))
        assertEquals(7, reading.temperature)
        assertEquals(WeatherCondition.Unknown, reading.condition)
    }

    @Test fun missingFieldsRejectTheReadingInsteadOfInventingValues() {
        for (field in listOf("temperature_2m", "weather_code", "is_day")) {
            val response = response(7.0, 2, 1).apply { remove(field) }
            assertThrows(Exception::class.java) { parseConditions(response) }
        }
    }

    @Test fun invalidTemperaturesAreNotDisplayedAsZeroOrClampedIntegers() {
        for (value in listOf("NaN", "Infinity", "-Infinity", "1e100", "-1e100", "not a number")) {
            val response = response(7.0, 2, 1).put("temperature_2m", value)
            assertThrows(Exception::class.java) { parseConditions(response) }
        }
    }

    @Test fun invalidDaylightAndFractionalCodesAreRejected() {
        for (value in listOf(-1.0, 2.0, .5)) {
            assertThrows(IllegalArgumentException::class.java) {
                parseConditions(response(7.0, 0, 1).put("is_day", value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseConditions(response(7.0, 0, 1).put("weather_code", 1.5))
        }
    }

    private fun assertCodes(expected: WeatherCondition, vararg codes: Int) {
        for (code in codes) assertEquals("WMO code $code", expected, WeatherCondition.fromCode(code))
    }

    private fun response(temperature: Double, code: Int, daylight: Int) = JSONObject()
        .put("temperature_2m", temperature).put("weather_code", code).put("is_day", daylight)
}

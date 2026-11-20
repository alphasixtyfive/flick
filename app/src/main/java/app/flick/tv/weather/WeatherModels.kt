package app.flick.tv.weather

data class WeatherLocation(val latitude: Double, val longitude: Double, val name: String, val fahrenheit: Boolean = false)

data class WeatherReading(val temperature: Int, val code: Int, val isDay: Boolean = true) {
    val condition: WeatherCondition get() = WeatherCondition.fromCode(code)
}

enum class WeatherCondition(val label: String) {
    Clear("Clear"), PartlyCloudy("Partly cloudy"), Overcast("Overcast"), Fog("Fog"),
    Drizzle("Drizzle"), Rain("Rain"), Snow("Snow"), Thunderstorm("Thunderstorm"), Unknown("Weather");

    companion object {
        /** Open-Meteo's WMO condition codes; unfamiliar codes do not imply clear weather. */
        fun fromCode(code: Int): WeatherCondition = when (code) {
            0, 1 -> Clear
            2 -> PartlyCloudy
            3 -> Overcast
            45, 48 -> Fog
            51, 53, 55, 56, 57 -> Drizzle
            61, 63, 65, 66, 67, 80, 81, 82 -> Rain
            71, 73, 75, 77, 85, 86 -> Snow
            95, 96, 97, 99 -> Thunderstorm
            else -> Unknown
        }
    }
}

data class PlaceResult(val name: String, val region: String, val latitude: Double, val longitude: Double)

package app.flick.weather

data class WeatherLocation(val latitude: Double, val longitude: Double, val name: String, val fahrenheit: Boolean = false)

data class WeatherReading(val temperature: Int, val code: Int)

data class PlaceResult(val name: String, val region: String, val latitude: Double, val longitude: Double)

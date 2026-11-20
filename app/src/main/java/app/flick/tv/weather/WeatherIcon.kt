package app.flick.tv.weather

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

internal val WeatherReading.icon: ImageVector
    get() = when (condition) {
        WeatherCondition.Clear -> if (isDay) Icons.Default.WbSunny else Icons.Default.DarkMode
        WeatherCondition.PartlyCloudy -> if (isDay) Icons.Default.WbCloudy else Icons.Default.NightsStay
        WeatherCondition.Overcast -> Icons.Default.Cloud
        WeatherCondition.Fog -> Icons.Default.Dehaze
        WeatherCondition.Drizzle, WeatherCondition.Rain -> Icons.Default.Grain
        WeatherCondition.Snow -> Icons.Default.AcUnit
        WeatherCondition.Thunderstorm -> Icons.Default.Thunderstorm
        WeatherCondition.Unknown -> Icons.Default.HelpOutline
    }

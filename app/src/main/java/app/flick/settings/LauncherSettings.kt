package app.flick.settings

import app.flick.profiles.Profile
import app.flick.weather.WeatherLocation

enum class NavigationSound { Soft, System, Off }

data class LauncherSettings(
    val largeText: Boolean = false, val compactTiles: Boolean = true,
    val showHomeAssistant: Boolean = true, val showSettings: Boolean = true, val showWallpaper: Boolean = true,
    val showWeather: Boolean = true, val clock24: Boolean = true, val showContinue: Boolean = true,
    val navigationSound: NavigationSound = NavigationSound.Soft,
    val weatherLocation: WeatherLocation? = null,
    val favorites: List<String>? = null, val removedContinue: Set<String> = emptySet(),
    val profiles: List<Profile> = listOf(Profile("home", "Home")), val activeProfileId: String = "home",
)

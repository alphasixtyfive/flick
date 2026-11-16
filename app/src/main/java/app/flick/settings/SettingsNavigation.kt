package app.flick.settings

enum class SettingsPage(val title: String) {
    Root("Settings"), Home("Home screen"), Clock("Clock & weather"),
    TopControls("Top buttons"), Device("Device"), About("About"),
}

/** Back from the root closes settings; single choices use dialogs within their parent. */
fun SettingsPage.parent(): SettingsPage? = when (this) {
    SettingsPage.Root -> null
    SettingsPage.TopControls -> SettingsPage.Home
    else -> SettingsPage.Root
}

package app.flick.settings

object ProjectLinks {
    const val SOURCE = "https://github.com/alphasixtyfive/flick"
    const val ISSUES = "$SOURCE/issues"
    const val LICENCES = "$SOURCE/blob/main/THIRD_PARTY_NOTICES.md"
    const val DEVELOPER = "https://github.com/alphasixtyfive"
    const val SETUP = "$SOURCE#home-assistant"
    fun allowed(url: String) = url in setOf(SOURCE, ISSUES, LICENCES, DEVELOPER, SETUP)
}

package app.flick.tv.settings

object ProjectLinks {
    const val SOURCE = "https://github.com/alphasixtyfive/flick"
    const val REPOSITORY_ID = 1459553132L
    val releaseRepositories = setOf("alphasixtyfive/flick", "alphasixtyfive/flick-tv")
    const val ISSUES = "$SOURCE/issues"
    const val LICENCES = "$SOURCE/blob/main/THIRD_PARTY_NOTICES.md"
    const val DEVELOPER = "https://github.com/alphasixtyfive"
    const val SETUP = "$SOURCE#home-assistant"
    fun allowed(url: String) = url in setOf(SOURCE, ISSUES, LICENCES, DEVELOPER, SETUP)
}

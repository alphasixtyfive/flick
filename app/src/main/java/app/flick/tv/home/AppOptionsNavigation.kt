package app.flick.tv.home

/** Closing an app menu returns to its own caller; a retired menu cannot change navigation. */
internal fun appOptionsBackDestination(current: HomePanel?, menu: HomePanel.AppOptions): HomePanel? {
    if (current !== menu) return current
    return if (menu.fromAllApps) HomePanel.AllApps else null
}

internal inline fun runAppOptionsAction(isCurrent: () -> Boolean, action: () -> Unit) {
    if (isCurrent()) action()
}

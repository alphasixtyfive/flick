package app.flick.ui.components

/** One control owns a complete remote gesture; a new control cannot inherit its release. */
internal class RemotePress {
    enum class Release { Ignored, Click, Consumed }

    private var key: Int? = null
    private var longClicked = false
    private var generation = 0L

    /** Only an initial down starts a gesture. Repeats and overlapping buttons do not restart it. */
    fun down(code: Int, repeat: Int): Long? {
        if (repeat != 0 || key != null) return null
        key = code
        longClicked = false
        generation++
        return generation
    }

    /** A timeout may fire once, and only for the gesture that scheduled it. */
    fun longPress(token: Long): Boolean {
        if (key == null || token != generation || longClicked) return false
        longClicked = true
        return true
    }

    /** Android's long-press flag belongs only to a key whose initial down this control received. */
    fun longPressForKey(code: Int): Boolean = key == code && longPress(generation)

    fun up(code: Int, cancelled: Boolean = false): Release {
        if (key != code) return Release.Ignored
        val result = if (cancelled || longClicked) Release.Consumed else Release.Click
        cancel()
        return result
    }

    fun cancel() {
        key = null
        longClicked = false
        generation++
    }
}

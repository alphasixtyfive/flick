package app.flick.hometext

import org.json.JSONObject

/** One optional subscription on the already authenticated companion socket. */
internal class HomeTextSubscription(
    private val subscribe: (HomeTextContext) -> Int?,
    private val unsubscribe: (Int) -> Unit,
    private val publish: (HomeTextState) -> Unit,
) {
    private var context: HomeTextContext? = null
    private var subscription: Int? = null
    private var acknowledged = false
    private var revision = 0L

    @Synchronized fun update(value: HomeTextContext?): Int? {
        if (value == context) return null
        subscription?.let(unsubscribe)
        subscription = null
        context = value
        acknowledged = false
        revision = 0
        publish(HomeTextState(value, HomeTextStatus.Waiting))
        if (value == null) return null
        return subscribe(value).also { id ->
            subscription = id
            if (id == null) publish(HomeTextState(value, HomeTextStatus.Unavailable))
        }
    }

    /** Errors here never reject the main control/notification subscription. */
    @Synchronized fun handle(frame: JSONObject): Boolean {
        val id = subscription ?: return false
        if (frame.opt("id") != id) return false
        val expected = context ?: return false
        when (frame.opt("type")) {
            "result" -> {
                if (frame.opt("success") == true) acknowledged = true
                else {
                    acknowledged = false
                    subscription = null
                    val unsupported = frame.optJSONObject("error")?.opt("code") == "unknown_command"
                    publish(HomeTextState(expected,
                        if (unsupported) HomeTextStatus.Unsupported else HomeTextStatus.Unavailable))
                }
            }
            "event" -> if (acknowledged) {
                val value = runCatching { HomeTextProtocol.read(frame.getJSONObject("event")) }.getOrNull()
                if (value == null) publish(HomeTextState(expected, HomeTextStatus.Unavailable))
                else if (value.profileId == expected.profile.id && value.generation == expected.generation && value.revision > revision) {
                    revision = value.revision
                    publish(HomeTextState(expected, HomeTextStatus.Connected, value))
                }
            }
        }
        return true
    }

    @Synchronized fun timeout(id: Int) {
        if (subscription != id || acknowledged) return
        subscription = null
        unsubscribe(id)
        publish(HomeTextState(context, HomeTextStatus.Unavailable))
    }

    @Synchronized fun close() {
        subscription?.let(unsubscribe)
        subscription = null
        acknowledged = false
        revision = 0
        publish(HomeTextState(context, HomeTextStatus.Waiting))
        context = null
    }
}

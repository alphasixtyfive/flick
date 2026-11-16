package app.flick.homeassistant

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/** Actual events race the entire POST-and-poll path, including a GET already in flight. */
internal object HaCommandConfirmation {
    suspend fun await(
        reported: Flow<HaControl>,
        matches: (HaControl) -> Boolean,
        send: suspend () -> Unit,
        readConfirmed: suspend () -> Boolean
    ) = coroutineScope {
        val live = async(start = CoroutineStart.UNDISPATCHED) { reported.first { matches(it) } }
        val polling = async {
            send()
            while (isActive) {
                if (readConfirmed()) return@async
                delay(200)
            }
        }
        try {
            select<Unit> {
                live.onAwait { polling.cancel() }
                polling.onAwait { live.cancel() }
            }
        } finally {
            live.cancel()
            polling.cancel()
        }
    }
}

package app.flick.integration

import org.json.JSONObject

/** Status acknowledgements prove the logical TV session still exists, beyond a live WebSocket. */
internal class BridgeStatusAcks {
    enum class Result { Ignored, Accepted, Rejected }
    private val pending = LinkedHashMap<Int, Long>()

    @Synchronized fun sent(id: Int, nowNanos: Long = System.nanoTime()): Boolean {
        if (pending.size >= 32 || id in pending) return false
        pending[id] = nowNanos
        return true
    }

    @Synchronized fun acknowledge(frame: JSONObject): Result {
        if (frame.opt("type") != "result") return Result.Ignored
        val number = frame.opt("id") as? Number ?: return Result.Ignored
        val id = number.toInt()
        if (number.toDouble() != id.toDouble() || pending.remove(id) == null) return Result.Ignored
        return if (frame.opt("success") == true) Result.Accepted else Result.Rejected
    }

    @Synchronized fun expired(nowNanos: Long = System.nanoTime()): Boolean =
        pending.values.any { nowNanos - it >= 10_000_000_000L }

    @Synchronized fun remainingMillis(nowNanos: Long = System.nanoTime()): Long? =
        pending.values.minOfOrNull { sent ->
            val remaining = (10_000_000_000L - (nowNanos - sent)).coerceAtLeast(0)
            (remaining + 999_999) / 1_000_000
        }
}

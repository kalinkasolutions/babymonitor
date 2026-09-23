package ch.kalinka.babymonitor.net

/**
 * Lets each signed signal through once.
 *
 * A signature stays good for as long as the freshness window lasts, so anything that can capture
 * one — the backend relaying it included — can hand the same message back inside that minute
 * without forging anything. A replayed stop ends a call, and a monitor that has been silently
 * stopped is the failure this app cannot have.
 *
 * Kept to the window rather than for ever: a signature that could no longer be believed on its
 * age has nothing left to remember about it.
 *
 * Not synchronised. Every caller is on the one thread the call state machine runs on, which is
 * what keeps the two paths — the WiFi and the hub — from racing each other through it.
 */
class SignalReplayGuard(private val windowMillis: Long = SignalFreshnessWindowMillis) {
    private val seen = mutableMapOf<String, Long>()

    /** True the first time this exact message is offered, false every time after. */
    fun accept(signal: SignalMessage, now: Long = System.currentTimeMillis()): Boolean {
        // Stamped in the future by a phone running ahead, this stays remembered for longer than
        // the window rather than less, which is the side to err on.
        seen.entries.removeIf { now - it.value > windowMillis }

        val key = "${signal.fromDeviceId}\n${signal.signature}"
        return seen.put(key, signal.sentAt) == null
    }
}

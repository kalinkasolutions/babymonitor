package ch.kalinka.babymonitor.device

import ch.kalinka.babymonitor.net.Availability

/** What a paired phone can do right now, said the same way in the device list and the watch list. */
enum class Reachability {
    Ready,
    NotArmed,
    InACall,
    Unreachable;

    val canBeWatched: Boolean
        get() = this == Ready

    fun line(name: String): String = when (this) {
        Ready -> "Ready"
        NotArmed -> "Not set up as a monitor. On $name, turn on “Use this phone as the monitor”."
        InACall -> "In a call with another phone"
        Unreachable -> "Not reachable. Open the app on it."
    }

    companion object {
        /** A phone that has said nothing about itself, an older app, is taken at its word that it is online. */
        fun of(online: Boolean, availability: Availability?): Reachability = when {
            !online -> Unreachable
            availability?.armed == false -> NotArmed
            availability?.busy == true -> InACall
            else -> Ready
        }
    }
}

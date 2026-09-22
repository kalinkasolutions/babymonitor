package ch.lqy.babyphone.nav

import kotlinx.serialization.Serializable

/**
 * Every place the signed-in app can be. The three [TabDestination] entries are the bottom bar;
 * everything else is pushed on top of them and hides the bar, because those are focused tasks
 * rather than places you switch between.
 */
sealed interface Destination {
    @Serializable
    data object Monitor : Destination, TabDestination

    @Serializable
    data object Devices : Destination, TabDestination

    @Serializable
    data object Settings : Destination, TabDestination

    @Serializable
    data object Account : Destination

    @Serializable
    data object Connect : Destination
}

/** Marker for the destinations that appear in the bottom bar. */
sealed interface TabDestination

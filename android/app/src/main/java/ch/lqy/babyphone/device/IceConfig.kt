package ch.lqy.babyphone.device

import ch.lqy.babyphone.net.IceServersDto

/**
 * The ICE configuration a peer connection is built from, and what it means for this household.
 *
 * Fetched rather than hard-coded because the relay credential expires: a phone that has been
 * sitting in a nursery for a week has to be able to get a fresh one before the next connection,
 * not during it.
 */
data class IceConfig(val servers: IceServersDto) {
    val stunUrls = servers.urls.filter { it.startsWith("stun:") }
    val turnUrls = servers.urls.filter { it.startsWith("turn:") || it.startsWith("turns:") }

    /** Without a relay, a connection that cannot be made directly simply cannot be made. */
    val hasRelay = turnUrls.isNotEmpty() && servers.credential.isNotEmpty()
}

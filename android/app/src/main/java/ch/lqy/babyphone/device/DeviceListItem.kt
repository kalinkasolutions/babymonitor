package ch.lqy.babyphone.device

import ch.lqy.babyphone.net.DeviceDto

/**
 * A device as the list shows it: what the server says, plus what this phone has worked out for
 * itself — whether the key is confirmed, whether the row is this phone, and whether it is even
 * this account's to remove.
 */
data class DeviceListItem(
    val device: DeviceDto,
    val trust: KeyTrust,
    val isThisPhone: Boolean,
    val isMine: Boolean
)

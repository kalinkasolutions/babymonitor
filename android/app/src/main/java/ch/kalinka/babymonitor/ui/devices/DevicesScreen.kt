package ch.kalinka.babymonitor.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.device.DeviceListItem
import ch.kalinka.babymonitor.device.DevicesViewModel
import ch.kalinka.babymonitor.net.DeviceDto
import ch.kalinka.babymonitor.ui.ErrorText
import ch.kalinka.babymonitor.ui.theme.otherDeviceContainer
import java.time.Duration
import java.time.Instant

/**
 * The account's phones, and the way to add another. Pairing itself is a pushed screen rather than
 * something inline here: it is a focused task with its own camera and its own back stack.
 */
@Composable
fun DevicesScreen(onConnect: () -> Unit, viewModel: DevicesViewModel = viewModel()) {
    val devices by viewModel.devices.collectAsState()
    val error by viewModel.error.collectAsState()
    var pendingRemoval by remember { mutableStateOf<DeviceListItem?>(null) }
    var pendingUnlink by remember { mutableStateOf<DeviceDto?>(null) }
    var comparing by remember { mutableStateOf<DeviceListItem?>(null) }

    // Coming back from pairing is the usual way a phone appears here, and the screen behind it
    // was never told.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    comparing?.let { item ->
        CompareDialog(
            device = item.device,
            trust = item.trust,
            safetyNumber = viewModel.safetyNumberFor(item.device),
            onMatches = {
                viewModel.confirmByComparison(item.device)
                comparing = null
            },
            onDismiss = { comparing = null }
        )
    }

    pendingUnlink?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            title = { Text("Unlink ${device.ownerName}?") },
            text = {
                Text(
                    "Every phone belonging to ${device.ownerName} goes out of this list, and " +
                        "yours out of theirs. Nobody loses anything else."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unlink(device.ownerId)
                    pendingUnlink = null
                }) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { pendingUnlink = null }) { Text("Cancel") } }
        )
    }

    pendingRemoval?.let { item ->
        RemovalDialog(
            device = item.device,
            isThisPhone = item.isThisPhone,
            onConfirm = {
                viewModel.revoke(item.device.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (devices.isEmpty()) {
            EmptyState(error)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { ErrorText(error) }

                // Two sections and no more, whoever turns up: one heading per person would grow
                // a list of headings instead of a list of phones. Whose a shared phone is, and
                // what unlinking them costs, belongs on the phone's own card.
                val (mine, shared) = devices.partition { it.isMine }

                for ((heading, group) in listOf("Your phones" to mine, "Shared with you" to shared)) {
                    if (group.isEmpty()) {
                        continue
                    }

                    item(key = "heading-$heading") { SectionHeading(heading) }
                    items(group, key = { it.device.id }) { item ->
                        DeviceCard(
                            item = item,
                            onRemove = { pendingRemoval = item },
                            onUnlink = { pendingUnlink = item.device },
                            onCompare = { comparing = item },
                            onRefresh = viewModel::refresh
                        )
                    }
                }
                // Only worth saying while this phone is on its own; once there are two it is
                // just a line of text in the way.
                if (devices.size == 1) {
                    item {
                        Text(
                            text = "A monitor needs two phones — connect the other one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onConnect,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Connect another phone") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }
}

@Composable
private fun DeviceCard(
    item: DeviceListItem,
    onRemove: () -> Unit,
    onUnlink: () -> Unit,
    onCompare: () -> Unit,
    onRefresh: () -> Unit
) {
    val device = item.device

    // This phone is always first and deliberately does not look like the rest: it is the one row
    // that is about you rather than about something you are watching.
    val colors = if (item.isThisPhone) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    } else {
        CardDefaults.cardColors(containerColor = otherDeviceContainer())
    }

    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.isThisPhone) "${device.name} — this phone" else device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (!item.isMine) {
                    Text(
                        text = "${device.ownerName}'s phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (item.isThisPhone || device.isOnline) {
                        batteryLine(device)
                    } else {
                        "${batteryLine(device)} · not connected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Reinstalling the app makes a new identity and leaves the old registration
                // behind, identical in name to the live one. When every row is "Google Pixel 9",
                // when it was last heard from is the only thing that tells them apart.
                if (!item.isThisPhone && device.lastSeenAt != null) {
                    val silent = lastSeen(device.lastSeenAt)
                    Text(
                        text = silent.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (silent.stale) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "key ${device.keyFingerprint}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!item.isThisPhone) {
                    Spacer(Modifier.height(6.dp))
                    // The badge is the way in to confirming a key, which is why it is the thing
                    // you press: a phone stuck at 1 of 2 — or one whose key changed — would
                    // otherwise say what is wrong and offer nothing to do about it.
                    TrustBadge(item.trust, modifier = Modifier.clickable(onClick = onCompare))
                }
            }
            if (item.isThisPhone) {
                // Removing the phone you are holding only makes it register again on the next
                // launch, so the useful action here is to ask the server for a fresh picture.
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            } else if (item.isMine) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove ${device.name}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                // Named, and in words: this does not remove a phone, it drops everything shared
                // with that person. A bin here would say the same thing as the one above it and
                // mean something much larger.
                TextButton(onClick = onUnlink) {
                    Text(
                        text = "Unlink ${device.ownerName}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}


/** Charging state first, because a nursery phone off its charger is the thing worth noticing. */
private fun batteryLine(device: DeviceDto): String {
    val percent = device.batteryPercent?.let { "$it%" } ?: "battery unknown"
    return when (device.isCharging) {
        true -> "$percent, charging"
        false -> "$percent, on battery"
        null -> percent
    }
}

private data class LastSeen(val text: String, val stale: Boolean)

/**
 * How long since that phone said anything. A registration nobody has used for days is almost
 * always the one a reinstall left behind, and saying so is what makes it safe to remove.
 */
private fun lastSeen(value: String?): LastSeen {
    val instant = value?.let {
        runCatching { Instant.parse(if (it.endsWith("Z")) it else it + "Z") }.getOrNull()
    } ?: return LastSeen("Never seen", stale = true)

    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 2 -> LastSeen("Seen just now", stale = false)
        minutes < 60 -> LastSeen("Seen $minutes minutes ago", stale = false)
        minutes < 60 * 24 -> LastSeen("Seen ${minutes / 60} hours ago", stale = false)
        else -> LastSeen("Not seen for ${minutes / (60 * 24)} days", stale = true)
    }
}

@Composable
private fun EmptyState(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No phones registered", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This phone registers itself when it can reach the server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        ErrorText(error)
    }
}

@Composable
private fun SectionHeading(name: String) {
    Text(
        text = name,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun RemovalDialog(
    device: DeviceDto,
    isThisPhone: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${device.name}?") },
        text = {
            Text(
                when {
                    isThisPhone ->
                        "This is the phone you are holding. It will register itself again next " +
                            "time the app starts, as a new entry that has to be confirmed again."

                    else ->
                        "It stops being able to monitor. If it is still in use it will come back " +
                            "the next time it starts."
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

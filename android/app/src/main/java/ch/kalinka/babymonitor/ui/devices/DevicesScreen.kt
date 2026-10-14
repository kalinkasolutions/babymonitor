package ch.kalinka.babymonitor.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.kalinka.babymonitor.device.DeviceRow
import ch.kalinka.babymonitor.device.DevicesViewModel
import ch.kalinka.babymonitor.device.fingerprintOf
import ch.kalinka.babymonitor.ui.theme.otherDeviceContainer

/** This phone and the phones paired with it, and the way to pair another. */
@Composable
fun DevicesScreen(onConnect: () -> Unit, viewModel: DevicesViewModel = viewModel()) {
    val devices by viewModel.devices.collectAsState()
    var pendingRemoval by remember { mutableStateOf<DeviceRow?>(null) }

    // Coming back from pairing is the usual way a phone appears here.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${row.phone.name}?") },
            text = {
                Text(
                    "This phone stops trusting it, so it can no longer listen here or be " +
                        "listened to. To use it again, pair the two phones again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forget(row.phone.id)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(devices, key = { it.phone.id }) { row ->
                DeviceCard(row, onRemove = { pendingRemoval = row })
            }

            if (devices.size <= 1) {
                item {
                    Text(
                        text = "A monitor needs two phones. Connect the other one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
private fun DeviceCard(row: DeviceRow, onRemove: () -> Unit) {
    val colors = if (row.isThisPhone) {
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
                    text = if (row.isThisPhone) "${row.phone.name} (this phone)" else row.phone.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = row.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "key ${fingerprintOf(row.phone.publicKey)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!row.isThisPhone) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove ${row.phone.name}",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

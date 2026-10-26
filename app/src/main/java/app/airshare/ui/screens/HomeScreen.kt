package app.airshare.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.airshare.connection.ReceiveStatus
import app.airshare.connection.UnavailableReason
import app.airshare.core.Formatting
import app.airshare.data.HistoryEntry
import app.airshare.ui.components.RadarPulse
import app.airshare.ui.components.SectionLabel

@Composable
fun HomeScreen(
    status: ReceiveStatus,
    enabled: Boolean,
    history: List<HistoryEntry>,
    onToggle: (Boolean) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onFixPermissions: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AirShare", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onSettings) { Icon(Icons.Default.Settings, "Settings", tint = cs.onSurfaceVariant) }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            RadarPulse(active = status is ReceiveStatus.Active) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Share, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item {
            val (title, sub) = when (status) {
                ReceiveStatus.Off -> "Receiving is off" to "Turn it on so nearby phones can send you files."
                ReceiveStatus.Starting -> "Getting ready…" to "Starting up."
                is ReceiveStatus.Active -> "Ready to receive" to "Visible to nearby phones as ${status.name}"
                is ReceiveStatus.Paused -> "Paused" to status.reason
                is ReceiveStatus.Unavailable -> when (status.reason) {
                    UnavailableReason.PERMISSIONS -> "Permission needed" to "AirShare needs Nearby permissions to receive."
                    UnavailableReason.BLUETOOTH_OFF -> "Bluetooth is off" to "Turn it on to be discoverable."
                    UnavailableReason.ERROR -> "Temporarily unavailable" to "Something stopped receiving. ${status.detail}".trim()
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                if (status is ReceiveStatus.Unavailable) {
                    when (status.reason) {
                        UnavailableReason.PERMISSIONS -> TextButton(onFixPermissions) { Text("Grant permissions") }
                        UnavailableReason.BLUETOOTH_OFF -> TextButton(onOpenBluetooth) { Text("Open Bluetooth settings") }
                        UnavailableReason.ERROR -> TextButton(onRetry) { Text("Try again") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Receive nearby files", style = MaterialTheme.typography.titleMedium)
                        Text("Lets you accept files without opening the app", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = onToggle)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Recent", Modifier.weight(1f))
                TextButton(onOpenDownloads) { Text("Received files") }
            }
        }
        if (history.isEmpty()) {
            item {
                Text("Nothing yet. In Gallery or Files, select something, tap Share and choose AirShare.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 12.dp))
            }
        } else {
            items(history, key = { it.id }) { e -> HistoryRow(e) { onOpenEntry(e) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val openable = !e.sent && e.openUri != null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = openable, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (e.ok) cs.primary else cs.error
        Box(Modifier.size(42.dp).clip(CircleShape).border(1.dp, cs.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (e.sent) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = tint)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            val first = if (e.fileCount > 1) "${e.firstName} +${e.fileCount - 1}" else e.firstName
            Text(first, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val who = if (e.sent) "To ${e.peerName}" else "From ${e.peerName}"
            Text("$who · ${Formatting.bytes(e.totalBytes)}" + if (e.ok) "" else " · Failed",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(DateUtils.getRelativeTimeSpanString(e.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

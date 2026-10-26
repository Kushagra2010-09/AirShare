package app.airshare.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.airshare.connection.CandStatus
import app.airshare.connection.CandUi
import app.airshare.connection.EndKind
import app.airshare.connection.FilesSummary
import app.airshare.connection.RecKind
import app.airshare.connection.SendUi
import app.airshare.ui.components.BigButton
import app.airshare.ui.components.FilesHeader
import app.airshare.ui.components.PhoneGlyph
import app.airshare.ui.components.Pill
import app.airshare.ui.components.RadarPulse
import app.airshare.ui.components.SuccessBadge
import app.airshare.ui.components.TransferPanel

@Composable
fun SendScreen(state: SendUi, onConfirm: (String) -> Unit, onCancel: () -> Unit, onClose: () -> Unit, onHome: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            SendUi.Idle -> Centered {
                Text("Nothing to send", style = MaterialTheme.typography.headlineMedium)
                Text("Select files in Gallery or Files, tap Share, then choose AirShare.", style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp)); BigButton("Close", onClose)
            }
            is SendUi.Searching -> Searching(state, onConfirm, onCancel)
            is SendUi.Transferring -> Centered { TransferPanel("Sending", "To ${state.peerName}", state.progress, onCancel) }
            is SendUi.Done -> Centered {
                SuccessBadge(); Spacer(Modifier.height(24.dp))
                Text("Sent", style = MaterialTheme.typography.headlineMedium)
                Text("${state.files.count} file(s) delivered to ${state.peerName}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                BigButton("Share another file", onClose); Spacer(Modifier.height(10.dp))
                BigButton("Home", onHome, primary = false)
            }
            is SendUi.Ended -> Centered {
                Text(when (state.kind) { EndKind.CANCELLED -> "Cancelled"; EndKind.EXPIRED -> "Request expired"; EndKind.FAILED -> "Couldn't send" },
                    style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp)); BigButton("Close", onClose)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { content() }
}

@Composable
private fun ColumnScope.Searching(s: SendUi.Searching, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf<CandUi?>(null) }
    val anyAccepted = s.candidates.any { it.status == CandStatus.ACCEPTED }
    val best = s.candidates.firstOrNull { it.recommended }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        FilesHeader(s.files)
        RadarPulse(active = !anyAccepted || s.candidates.size < 2, size = 170.dp) {
            Text("${s.secondsLeft}s", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Text(
            when (s.rec) {
                RecKind.NONE -> if (s.candidates.isEmpty()) "Looking for nearby phones…" else "Waiting for responses…"
                RecKind.ONLY_ONE -> "${best?.name ?: "A phone"} is ready. Check the code, then send."
                RecKind.CLEAR -> "${best?.name ?: "One phone"} looks closest. Check the code, then send."
                RecKind.AMBIGUOUS -> "Several phones are ready. Choose who should receive."
                RecKind.UNKNOWN -> "Several phones are ready. We can't tell which is closest, so please choose."
            },
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        if (s.candidates.isEmpty()) {
            Text("The other phone needs AirShare with \"Receive nearby files\" turned on, and Bluetooth on.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        s.candidates.forEach { c ->
            CandidateCard(c) { confirm = c }
            Spacer(Modifier.height(10.dp))
        }
    }
    BigButton("Cancel", onCancel, primary = false)

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Send to ${c.name}?") },
            text = {
                Text("${s.files.kinds} (${s.files.sizeText}) will be sent." +
                    if (c.code.isNotBlank()) " Code ${c.code} should match on their screen." else "")
            },
            confirmButton = { TextButton({ val id = c.endpointId; confirm = null; onConfirm(id) }) { Text("Send") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Not yet") } },
        )
    }
}

@Composable
private fun CandidateCard(c: CandUi, onSend: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surface,
        border = BorderStroke(if (c.recommended) 1.5.dp else 1.dp, if (c.recommended) cs.primary else cs.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneGlyph(tint = if (c.status == CandStatus.ACCEPTED) cs.primary else cs.onSurfaceVariant)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (c.recommended) { Spacer(Modifier.padding(start = 8.dp)); Pill("Suggested", cs.primary) }
                }
                if (c.model.isNotBlank() && c.status == CandStatus.ACCEPTED)
                    Text(c.model, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Text(
                    when (c.status) {
                        CandStatus.CONNECTING -> "Connecting…"
                        CandStatus.WAITING -> "Waiting for them to accept…"
                        CandStatus.ACCEPTED -> c.proximityLabel + if (c.code.isNotBlank()) " · Code ${c.code}" else ""
                        CandStatus.DECLINED -> "Declined"
                        CandStatus.FAILED -> c.note ?: "Unavailable"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
            if (c.status == CandStatus.ACCEPTED) Button(onSend, shape = MaterialTheme.shapes.small) { Text("Send") }
        }
    }
}

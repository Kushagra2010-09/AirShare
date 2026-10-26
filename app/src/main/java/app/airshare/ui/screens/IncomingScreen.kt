package app.airshare.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.airshare.connection.InPhase
import app.airshare.connection.IncomingUi
import app.airshare.ui.components.BigButton
import app.airshare.ui.components.PhoneGlyph
import app.airshare.ui.components.SuccessBadge
import app.airshare.ui.components.TransferPanel

@Composable
fun IncomingScreen(
    i: IncomingUi,
    onAccept: () -> Unit, onDecline: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit,
    onOpen: () -> Unit, onFolder: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        when (i.phase) {
            InPhase.INVITED -> {
                PhoneGlyph(tint = cs.primary)
                Spacer(Modifier.height(16.dp))
                Text("Nearby sharing request", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(i.senderName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("wants to share with you", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                i.files?.let { f ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surface, border = BorderStroke(1.dp, cs.outlineVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(f.kinds, style = MaterialTheme.typography.titleMedium)
                            Text("${f.count} ${if (f.count == 1) "file" else "files"} · ${f.sizeText}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            f.names.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                            if (f.count > f.names.size) Text("+ ${f.count - f.names.size} more", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Encrypted, direct connection." + if (i.code.isNotBlank()) " Code ${i.code} should match on the sender's screen. If it doesn't, decline." else "",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                BigButton("Accept", onAccept)
                Spacer(Modifier.height(10.dp))
                BigButton("Decline", onDecline, primary = false)
            }
            InPhase.ACCEPTED_WAIT -> {
                CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(24.dp))
                Text("Waiting for ${i.senderName}", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text("They'll confirm that you're the right person, then the transfer starts.", style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                BigButton("Cancel", onCancel, primary = false)
            }
            InPhase.RECEIVING -> {
                val p = i.progress
                if (p != null) TransferPanel("Receiving", "From ${i.senderName}", p, onCancel)
                else CircularProgressIndicator(strokeWidth = 3.dp)
            }
            InPhase.DONE -> {
                SuccessBadge()
                Spacer(Modifier.height(24.dp))
                Text("Received", style = MaterialTheme.typography.headlineMedium)
                Text("${i.files?.count ?: 0} file(s) from ${i.senderName} · saved to Downloads/AirShare", style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                if (i.savedUri != null) { BigButton("Open", onOpen); Spacer(Modifier.height(10.dp)) }
                BigButton("View received files", onFolder, primary = false)
                Spacer(Modifier.height(10.dp))
                BigButton("Done", onDismiss, primary = false)
            }
            InPhase.FAILED, InPhase.CANCELLED -> {
                Text(if (i.phase == InPhase.FAILED) "Transfer didn't finish" else "Request closed", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(i.message ?: "Nothing was saved.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Text("Incomplete or unverified files are never kept.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                BigButton("OK", onDismiss)
            }
            else -> {}
        }
    }
}

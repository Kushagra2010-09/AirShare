package app.airshare.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.airshare.core.DeviceInfo
import app.airshare.core.Permissions
import app.airshare.data.AppSettings
import app.airshare.ui.components.BigButton
import app.airshare.ui.components.SectionLabel

class SettingsActions(
    val setName: (String) -> Unit,
    val setReceiving: (Boolean) -> Unit,
    val setPauseOff: (Boolean) -> Unit,
    val setBeacon: (Boolean) -> Unit,
    val setTheme: (String) -> Unit,
    val setDynamic: (Boolean) -> Unit,
    val setTimeout: (Int) -> Unit,
    val setProgressNotif: (Boolean) -> Unit,
    val clearHistory: () -> Unit,
)

@Composable
fun SettingsScreen(s: AppSettings, a: SettingsActions, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {

            SectionLabel("This device")
            val focus = LocalFocusManager.current
            var name by remember(s.displayName) { mutableStateOf(s.displayName) }
            val latest = rememberUpdatedState(name)
            DisposableEffect(Unit) { onDispose { if (latest.value != s.displayName) a.setName(latest.value) } }
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Name others see") }, placeholder = { Text(DeviceInfo.resolveName(ctx, "")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { a.setName(name); focus.clearFocus() }),
                shape = MaterialTheme.shapes.medium,
            )
            Text("Shown to people nearby. It is only a label, never proof of who someone is.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

            SectionLabel("Receiving")
            ToggleRow("Background receiving", "Keeps this phone ready to receive. Shows a small notification.", s.receivingEnabled, a.setReceiving)
            Text("Who can see this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!s.pauseWhenScreenOff, { a.setPauseOff(false) }, { Text("While receiving is on") })
                FilterChip(s.pauseWhenScreenOff, { a.setPauseOff(true) }, { Text("Only screen on") })
            }
            ToggleRow("Help senders pick the closest phone", "Sends a tiny Bluetooth signal so senders can estimate proximity. Turn off for maximum privacy.", s.proximityBeacon, a.setBeacon)
            Text("Requests always need your approval. AirShare never accepts files automatically.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

            SectionLabel("Requests")
            Text("Request stays open for", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60, 90, 180).forEach { sec -> FilterChip(s.inviteTimeoutSec == sec, { a.setTimeout(sec) }, { Text("${sec}s") }) }
            }

            SectionLabel("Notifications")
            ToggleRow("Show transfer progress", "Progress and results in the notification shade.", s.showProgressNotification, a.setProgressNotif)
            TextButton({ openIntent(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }) {
                Text("System notification settings")
            }

            SectionLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, label) ->
                    FilterChip(s.theme == k, { a.setTheme(k) }, { Text(label) })
                }
            }
            if (Build.VERSION.SDK_INT >= 31) ToggleRow("Use system colors", "Match your wallpaper colors instead of AirShare's palette.", s.dynamicColor, a.setDynamic)

            SectionLabel("Battery and background")
            val saver = Permissions.powerSave(ctx)
            val unrestricted = Permissions.ignoringBatteryOptimizations(ctx)
            StatusLine("Battery saver", if (saver) "On — may pause discovery" else "Off")
            StatusLine("Battery optimization", if (unrestricted) "Unrestricted" else "Optimized (default)")
            Text("Some phone makers stop background apps regardless of settings. If receiving stops, exempt AirShare from battery optimization.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            TextButton({ openIntent(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open battery optimization") }

            SectionLabel("Privacy")
            Surface(color = cs.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, cs.outlineVariant)) {
                Text(
                    "• No account, no server, no internet: files go directly between the two phones.\n" +
                        "• Connections are encrypted by Google's Nearby Connections, and both phones show a 4-digit code you can compare.\n" +
                        "• Nothing is received without your approval, and nothing is sent until the sender picks one receiver.\n" +
                        "• Nearby Connections is part of Google Play services and needs it to be present and up to date.\n" +
                        "• Limits: someone close by can see that an AirShare phone is nearby and its display name. A matching code does not protect you if you don't compare it.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                )
            }
            TextButton(a.clearHistory) { Text("Clear transfer history") }

            SectionLabel("About and diagnostics")
            StatusLine("Version", "0.1.0")
            StatusLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            val pm = ctx.packageManager
            StatusLine("Ultra-wideband hardware", if (pm.hasSystemFeature("android.hardware.uwb")) "Present — precise ranging not implemented yet" else "Not present")
            StatusLine("Bluetooth LE", if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "Supported" else "Not supported")
            val missing = Permissions.missing(ctx)
            StatusLine("Permissions", if (missing.isEmpty()) "All granted" else "${missing.size} missing")
            if (missing.isNotEmpty()) BigButton("Open app permissions", {
                openIntent(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }, primary = false)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun StatusLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

private fun openIntent(ctx: Context, i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

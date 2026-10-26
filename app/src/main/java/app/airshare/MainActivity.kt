package app.airshare

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.airshare.background.AirShareService
import app.airshare.core.Permissions
import app.airshare.data.AppSettings
import app.airshare.data.HistoryEntry
import app.airshare.ui.screens.HomeScreen
import app.airshare.ui.screens.IncomingScreen
import app.airshare.ui.screens.SettingsActions
import app.airshare.ui.screens.SettingsScreen
import app.airshare.ui.theme.AirShareTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as AirShareApp

    private var pendingEnable = false
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingEnable && Permissions.missingRequired(this).isEmpty()) setReceiving(true)
        pendingEnable = false
        lifecycleScope.launch { app.engine.onSettings(app.settings.current()) }
    }

    private fun setReceiving(on: Boolean) {
        lifecycleScope.launch {
            app.settings.setReceiving(on)
            app.engine.onSettings(app.settings.current())
            if (on) AirShareService.start(this@MainActivity)
        }
    }

    private fun requestReceiving() {
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) setReceiving(true)
        else { pendingEnable = true; permLauncher.launch(missing.toTypedArray()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle(AppSettings())
            AirShareTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { Root(settings) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Foreground: safe moment to (re)start the service and re-evaluate permissions / Bluetooth state.
        lifecycleScope.launch {
            val s = app.settings.current()
            app.engine.onSettings(s)
            if (s.receivingEnabled && Permissions.missingRequired(this@MainActivity).isEmpty()) AirShareService.start(this@MainActivity)
        }
    }

    @Composable
    private fun Root(settings: AppSettings) {
        val status by app.engine.receiveStatus.collectAsStateWithLifecycle()
        val incoming by app.engine.incoming.collectAsStateWithLifecycle()
        val history by app.history.flow.collectAsStateWithLifecycle(emptyList())
        var screen by remember { mutableStateOf("home") }

        val active = incoming.firstOrNull()
        if (active != null) {
            IncomingScreen(
                active,
                onAccept = { app.engine.acceptInvite(active.endpointId) },
                onDecline = { app.engine.declineInvite(active.endpointId) },
                onCancel = { app.engine.cancelIncoming(active.endpointId) },
                onDismiss = { app.engine.dismissIncoming(active.endpointId) },
                onOpen = { active.savedUri?.let { openFile(it, active.savedMime) } },
                onFolder = { openDownloads(); app.engine.dismissIncoming(active.endpointId) },
            )
            return
        }
        if (screen == "settings") {
            BackHandler { screen = "home" }
            val scope = lifecycleScope
            SettingsScreen(
                settings,
                SettingsActions(
                    setName = { v -> scope.launch { app.settings.setName(v); app.engine.onSettings(app.settings.current()) } },
                    setReceiving = { on -> if (on) requestReceiving() else setReceiving(false) },
                    setPauseOff = { v -> scope.launch { app.settings.setPauseWhenScreenOff(v) } },
                    setBeacon = { v -> scope.launch { app.settings.setProximityBeacon(v) } },
                    setTheme = { v -> scope.launch { app.settings.setTheme(v) } },
                    setDynamic = { v -> scope.launch { app.settings.setDynamicColor(v) } },
                    setTimeout = { v -> scope.launch { app.settings.setInviteTimeout(v) } },
                    setProgressNotif = { v -> scope.launch { app.settings.setProgressNotification(v) } },
                    clearHistory = { scope.launch { app.history.clear() } },
                ),
                onBack = { screen = "home" },
            )
            return
        }
        HomeScreen(
            status = status, enabled = settings.receivingEnabled, history = history,
            onToggle = { on -> if (on) requestReceiving() else setReceiving(false) },
            onSettings = { screen = "settings" },
            onRetry = { app.engine.retryReceiving() },
            onFixPermissions = { requestReceiving() },
            onOpenBluetooth = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onOpenEntry = { e: HistoryEntry -> e.openUri?.let { openFile(it, e.mime) } },
            onOpenDownloads = { openDownloads() },
        )
    }

    private fun openFile(uri: String, mime: String?) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }
    }

    private fun openDownloads() { runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } }
}

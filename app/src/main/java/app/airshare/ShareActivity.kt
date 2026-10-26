package app.airshare

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.airshare.background.AirShareService
import app.airshare.connection.SendUi
import app.airshare.core.Permissions
import app.airshare.data.AppSettings
import app.airshare.notifications.Notifier
import app.airshare.transfer.FileIo
import app.airshare.ui.screens.SendScreen
import app.airshare.ui.theme.AirShareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Target of the system Share Sheet. Also reopened (ACTION_SHOW_SEND) from the "Receiver ready" notification. */
class ShareActivity : ComponentActivity() {
    private val app get() = application as AirShareApp
    private var pendingUris: List<Uri> = emptyList()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingUris.isNotEmpty()) begin(pendingUris)
        pendingUris = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle(AppSettings())
            val state by app.engine.sendState.collectAsStateWithLifecycle()
            AirShareTheme(settings.theme, settings.dynamicColor) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SendScreen(
                        state,
                        onConfirm = { app.engine.confirmRecipient(it) },
                        onCancel = { app.engine.cancelSend() },
                        onClose = { app.engine.dismissSend(); finish() },
                        onHome = { app.engine.dismissSend(); startActivity(Intent(this@ShareActivity, MainActivity::class.java)); finish() },
                    )
                }
            }
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onStart() { super.onStart(); app.engine.sendUiVisible = true }
    override fun onStop() { app.engine.sendUiVisible = false; super.onStop() }

    private fun handle(i: Intent) {
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.ifEmpty {
            // Some apps only provide ClipData
            val c = i.clipData
            if (c == null || i.action == Notifier.ACTION_SHOW_SEND) emptyList() else (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }
        }.filter { it.scheme == "content" || it.scheme == "file" }.distinct().take(200)
        if (uris.isEmpty()) return            // opened from a notification or without content: just show current state
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) begin(uris) else { pendingUris = uris; permLauncher.launch(missing.toTypedArray()) }
    }

    private fun begin(uris: List<Uri>) {
        lifecycleScope.launch {
            app.engine.onSettings(app.settings.current())
            val files = withContext(Dispatchers.IO) { FileIo.describe(applicationContext, uris) }
            // Hand the temporary read grants to the service so they outlive this screen.
            val clip = files.firstOrNull()?.let { f -> ClipData.newRawUri("airshare", f.uri).also { c -> files.drop(1).forEach { c.addItem(ClipData.Item(it.uri)) } } }
            AirShareService.start(this@ShareActivity, clip?.let { Intent().apply { clipData = it } })
            app.engine.startSend(files)
        }
    }
}

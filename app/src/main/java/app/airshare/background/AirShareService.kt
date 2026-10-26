package app.airshare.background

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.airshare.AirShareApp
import app.airshare.connection.InPhase
import app.airshare.connection.ReceiveStatus
import app.airshare.connection.SendUi
import app.airshare.connection.UnavailableReason
import app.airshare.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the process alive ONLY while there is a reason to:
 * receiving is enabled, a send is in progress, or a transfer is running. It owns no radios itself; it forwards
 * settings / Bluetooth / screen changes to [app.airshare.connection.AirShareEngine] (event-driven, no polling)
 * and stops itself when idle.
 */
class AirShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: Notifier
    private var receiversRegistered = false

    private val system = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val engine = (application as AirShareApp).engine
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> engine.onScreen(true)
                Intent.ACTION_SCREEN_ON -> engine.onScreen(false)
                BluetoothAdapter.ACTION_STATE_CHANGED -> engine.onBluetoothChanged()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        val ok = runCatching {
            val n = notifier.service("Starting…")
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(Notifier.ID_SERVICE, n)
        }.isSuccess
        if (!ok) { stopSelf(); return }   // e.g. background start not allowed or permission revoked: don't crash-loop

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, system, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true

        val a = application as AirShareApp
        scope.launch {
            a.settings.flow.collect { a.engine.onSettings(it) }
        }
        scope.launch {
            combine(a.settings.flow, a.engine.receiveStatus, a.engine.sendState, a.engine.incoming) { s, r, snd, inc ->
                val sending = snd is SendUi.Searching || snd is SendUi.Transferring
                val receivingNow = inc.firstOrNull { it.phase == InPhase.RECEIVING }
                val busyIn = inc.any { it.phase == InPhase.INVITED || it.phase == InPhase.ACCEPTED_WAIT || it.phase == InPhase.RECEIVING }
                val needed = s.receivingEnabled || sending || busyIn
                val text = when {
                    snd is SendUi.Transferring -> "Sending files…"
                    snd is SendUi.Searching -> "Looking for nearby phones…"
                    receivingNow != null -> "Receiving from ${receivingNow.senderName}"
                    r is ReceiveStatus.Active -> "Ready to receive as ${r.name}"
                    r is ReceiveStatus.Paused -> r.reason
                    r is ReceiveStatus.Starting -> "Starting…"
                    r is ReceiveStatus.Unavailable -> when (r.reason) {
                        UnavailableReason.PERMISSIONS -> "Receiving paused: permission needed"
                        UnavailableReason.BLUETOOTH_OFF -> "Receiving paused: Bluetooth is off"
                        UnavailableReason.ERROR -> "Receiving unavailable right now"
                    }
                    else -> "Idle"
                }
                needed to text
            }.collect { (needed, text) ->
                if (!needed) { stopSelf() } else runCatching {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifier.ID_SERVICE, notifier.service(text))
                }
            }
        }
    }

    // START_STICKY: if Android kills the process the system may recreate the service; it then re-reads the
    // saved "receiving" setting. Whether that happens depends on the OEM/battery policy (untested).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiversRegistered) runCatching { unregisterReceiver(system) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Safe to call repeatedly. Must be called while the app is visible, from a boot broadcast, or from the service itself. */
        fun start(context: Context, grant: Intent? = null): Boolean = runCatching {
            val i = Intent(context, AirShareService::class.java)
            if (grant != null) { i.clipData = grant.clipData; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ContextCompat.startForegroundService(context, i)
            true
        }.getOrDefault(false)
    }
}

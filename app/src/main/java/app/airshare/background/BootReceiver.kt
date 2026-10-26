package app.airshare.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.airshare.AirShareApp
import app.airshare.core.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Resumes background receiving after reboot or app update, only if the user left it enabled and permissions are intact. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as AirShareApp
                if (app.settings.current().receivingEnabled && Permissions.missingRequired(context).isEmpty()) {
                    AirShareService.start(context)
                }
            } finally { pending.finish() }
        }
    }
}

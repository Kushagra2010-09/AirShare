package app.airshare.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.airshare.AirShareApp

/** Handles Accept / Decline / Cancel taps from notifications. No-ops if the process was restarted (state is in memory only). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = (context.applicationContext as AirShareApp).engine
        val id = intent.getStringExtra(Notifier.EXTRA_ENDPOINT)
        when (intent.action) {
            Notifier.ACTION_ACCEPT -> if (id != null) engine.acceptInvite(id)
            Notifier.ACTION_DECLINE -> if (id != null) engine.declineInvite(id)
            Notifier.ACTION_CANCEL_SEND -> engine.cancelSend()
        }
    }
}

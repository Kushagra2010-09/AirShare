package app.airshare.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.airshare.MainActivity
import app.airshare.R
import app.airshare.ShareActivity

class Notifier(private val ctx: Context) {
    companion object {
        const val CH_STATUS = "status"
        const val CH_REQUEST = "requests"
        const val CH_TRANSFER = "transfers"
        const val ID_SERVICE = 1
        const val ID_READY = 2
        const val ID_SEND = 3
        const val ACTION_ACCEPT = "app.airshare.ACCEPT"
        const val ACTION_DECLINE = "app.airshare.DECLINE"
        const val ACTION_CANCEL_SEND = "app.airshare.CANCEL_SEND"
        const val ACTION_SHOW_SEND = "app.airshare.SHOW_SEND"
        const val EXTRA_ENDPOINT = "endpoint"
        fun idFor(endpointId: String) = 100 + (endpointId.hashCode() and 0x7FFFFFFF) % 1000
    }

    private val nm = NotificationManagerCompat.from(ctx)

    init {
        val sys = ctx.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(NotificationChannel(CH_STATUS, "Background receiving", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Shown while AirShare is ready to receive or transfer"; setShowBadge(false) })
        sys.createNotificationChannel(NotificationChannel(CH_REQUEST, "Sharing requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Incoming requests and receivers that accepted yours" })
        sys.createNotificationChannel(NotificationChannel(CH_TRANSFER, "Transfers", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Transfer progress and results"; setShowBadge(false) })
    }

    private fun openMain(extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra(EXTRA_ENDPOINT, extra)
        return PendingIntent.getActivity(ctx, extra?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun broadcast(action: String, endpoint: String?): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action)
        if (endpoint != null) i.putExtra(EXTRA_ENDPOINT, endpoint)
        return PendingIntent.getBroadcast(ctx, (action + endpoint).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun service(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS).setSmallIcon(R.drawable.ic_stat_airshare)
            .setContentTitle("AirShare").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setContentIntent(openMain())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()

    @SuppressLint("MissingPermission")
    private fun post(id: Int, n: Notification) { runCatching { if (nm.areNotificationsEnabled()) nm.notify(id, n) } }

    fun request(endpointId: String, sender: String, summary: String, timeoutSec: Int) {
        val n = NotificationCompat.Builder(ctx, CH_REQUEST).setSmallIcon(R.drawable.ic_stat_airshare)
            .setContentTitle("Nearby sharing request").setContentText("$sender wants to share $summary with you.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$sender wants to share $summary with you."))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setTimeoutAfter(timeoutSec * 1000L).setContentIntent(openMain(endpointId))
            .addAction(0, "Accept", broadcast(ACTION_ACCEPT, endpointId))
            .addAction(0, "Decline", broadcast(ACTION_DECLINE, endpointId)).build()
        post(idFor(endpointId), n)
    }

    fun receiverReady(name: String) {
        val show = PendingIntent.getActivity(
            ctx, 77, Intent(ctx, ShareActivity::class.java).setAction(ACTION_SHOW_SEND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_REQUEST).setSmallIcon(R.drawable.ic_stat_airshare)
            .setContentTitle("Receiver ready").setContentText("$name accepted your sharing request.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(show)
            .addAction(0, "Review and Share", show)
            .addAction(0, "Cancel", broadcast(ACTION_CANCEL_SEND, null)).build()
        post(ID_READY, n)
    }

    fun ongoing(id: Int, title: String, text: String, cur: Int = 0, max: Int = 0, endpoint: String? = null) {
        val b = NotificationCompat.Builder(ctx, CH_TRANSFER).setSmallIcon(R.drawable.ic_stat_airshare)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(if (endpoint != null) openMain(endpoint) else openMain())
        if (max > 0) b.setProgress(max, cur, false) else b.setProgress(0, 0, true)
        post(id, b.build())
    }

    fun finished(id: Int, title: String, text: String, endpoint: String? = null) {
        post(id, NotificationCompat.Builder(ctx, CH_TRANSFER).setSmallIcon(R.drawable.ic_stat_airshare)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setContentIntent(if (endpoint != null) openMain(endpoint) else openMain()).build())
    }

    fun cancel(id: Int) { runCatching { nm.cancel(id) } }
}

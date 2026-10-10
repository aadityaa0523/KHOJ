package app.linkfill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.linkfill.LinkFillApp
import app.linkfill.MainActivity

/**
 * Keeps a user-started session alive while the screen is off, with a visible notification and a Stop
 * button, so location is never shared silently. It does nothing else; the session lives in LinkSession.
 */
class SessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as LinkFillApp).session.stop()
            return START_NOT_STICKY
        }
        val sender = intent?.getBooleanExtra(EXTRA_SENDER, false) ?: false
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active sharing session", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (sender) "Sharing your location" else "Receiving a shared location")
            .setContentText(if (sender) "Only with the phone you confirmed. Tap Stop to end." else "Tap to open. Tap Stop to end.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_SENDER = "sender"
        private const val ACTION_STOP = "app.linkfill.STOP"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
    }
}

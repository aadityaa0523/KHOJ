package app.linkfill.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.linkfill.LinkFillApp
import app.linkfill.MainActivity

/**
 * Keeps the mesh relaying with the screen off, with a visible notification and a Stop button. While a fall
 * or panic countdown runs, the notification turns into a "Cancel SOS" prompt. The camera type is added only
 * while the Rewind buffer is running, because Android requires the camera permission for that type.
 */
class MeshService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as LinkFillApp
        when (intent?.action) {
            ACTION_STOP -> { app.mesh.stop(); return START_NOT_STICKY }
            ACTION_CANCEL -> { app.mesh.cancelCountdown(); return START_NOT_STICKY }
        }
        val camera = intent?.getBooleanExtra(EXTRA_CAMERA, false) == true
        val countdown = intent?.getStringExtra(EXTRA_COUNTDOWN)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "KHOJ mesh", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 3, Intent(this, MeshService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentIntent(open).setOngoing(true)
        if (countdown != null) {
            b.setContentTitle("$countdown: sending SOS in 10 s").setContentText("Tap Cancel if you are fine.")
                .addAction(Notification.Action.Builder(null, "Cancel SOS", cancel).build())
        } else {
            b.setContentTitle("KHOJ is on").setContentText(if (camera) "Mesh, safety monitor and Rewind camera running. Tap Stop to end." else "Sharing with your crew and relaying for others. Tap Stop to end.")
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (camera) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        startForeground(NOTIFICATION_ID, b.build(), types)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_COUNTDOWN = "countdown"
        private const val ACTION_STOP = "app.linkfill.MESH_STOP"
        private const val ACTION_CANCEL = "app.linkfill.SOS_CANCEL"
        private const val CHANNEL = "mesh"
        private const val NOTIFICATION_ID = 2
    }
}

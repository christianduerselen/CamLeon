package de.camleon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import de.camleon.web.ControlServer
import de.camleon.common.NetUtil

/**
 * Foreground service that keeps camera, microphone and the gimbal link alive while the activity is
 * not visible (screen locked, other app in front). Without it Android's camera service revokes the
 * camera about 5 s after the screen locks ("Access … has been restricted … isUidVisible false") and
 * the preview/stream go black. The service does no work itself — [CaptureSession] holds everything —
 * it only carries the foreground state and a notification with the browser control URL and a Quit
 * action ([AppQuit]).
 */
class CaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) { AppQuit.quit(this); return START_NOT_STICKY }
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, buildNotification(), types)
            else startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "foreground (camera|microphone|connectedDevice)")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quit = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val urls = NetUtil.controlUrls(ControlServer.DEFAULT_PORT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(if (urls.isEmpty()) getString(R.string.notif_text_plain) else getString(R.string.notif_text_url, urls.first()))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.quit), quit).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_QUIT = "de.camleon.action.QUIT"

        /** Start (or re-assert) the foreground service; call while the app is in the foreground. */
        fun start(context: Context) {
            val i = Intent(context, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
    }
}

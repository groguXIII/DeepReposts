package app.deepreposts

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
import android.os.PowerManager

/**
 * Держит приложение «живым» в свёрнутом виде: уведомление + блокировка сна процессора.
 * Сама работа идёт в MainActivity; сервис только не даёт системе её усыпить.
 */
class KeepAliveService : Service() {
    private var wl: PowerManager.WakeLock? = null

    companion object {
        const val CH = "dr"
        fun build(c: Context, text: String): Notification {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "DeepReposts", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("DeepReposts")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = build(this, i?.getStringExtra("text") ?: "Работаю…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1, n)
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dr:work")
            wl?.acquire(6 * 60 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

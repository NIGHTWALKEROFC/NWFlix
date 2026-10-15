package app.nwmovie.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.nwmovie.R
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.*

/** Keeps downloads alive while the app is in the background, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, build(0, 0), type)
        if (job == null) {
            job = scope.launch {
                ServiceLocator.downloads.summary.collect { (count, pct) ->
                    if (count == 0) { stopSelf() } else {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(count, pct))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(count: Int, pct: Int): Notification {
        val hide = ServiceLocator.prefs.value.hideNotif
        val text = if (hide) "Download in progress" else if (count == 0) "Preparing…" else "$count active • $pct%"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NWMovie downloads")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(100, pct, count == 0)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "downloads"
        private const val ID = 1001
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}

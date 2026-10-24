package app.nwmovie.data

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.nwmovie.MainActivity
import app.nwmovie.R
import kotlinx.coroutines.*

class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, ID, progress("Preparing the update…", 0, true), type)
        if (job == null) {
            job = scope.launch {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                Updater.state.collect { s ->
                    when (s) {
                        is Updater.State.Downloading -> {
                            val text = if (s.percent >= 0) "Downloading ${s.info.versionName} • ${s.percent}%" else "Downloading ${s.info.versionName}"
                            nm.notify(ID, progress(text, s.percent.coerceAtLeast(0), s.percent < 0))
                        }
                        is Updater.State.Installing -> nm.notify(ID, progress("Installing ${s.info.versionName}…", 0, true))
                        is Updater.State.Ready -> {
                            finish()
                            if (s.note.isNotBlank() && !Updater.visible()) postReady(this@UpdateService, openApp(this@UpdateService), s.info.versionName)
                        }
                        else -> finish()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progress(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("NWFlix update")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "updates"
        const val READY_CHANNEL = "update_ready"
        private const val ID = 1002

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW))
                nm.createNotificationChannel(NotificationChannel(READY_CHANNEL, "Update ready", NotificationManager.IMPORTANCE_HIGH))
            }
        }

        fun openApp(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun postReady(ctx: Context, target: PendingIntent, version: String) {
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, READY_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("NWFlix update is ready")
                .setContentText(if (version.isBlank()) "Tap to install" else "Tap to install version $version")
                .setContentIntent(target).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try { (ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(Updater.READY_NOTICE_ID, n) } catch (_: SecurityException) { }
        }
    }
}

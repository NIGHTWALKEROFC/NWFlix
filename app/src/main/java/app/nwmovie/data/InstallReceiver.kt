package app.nwmovie.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Receives the result of the system installer (asks for the confirmation screen when Android needs it). */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> { /* the app is replaced and restarted by Android */ }
            else -> Updater.state.value = Updater.State.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed")
        }
    }
}

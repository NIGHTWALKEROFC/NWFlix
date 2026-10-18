package app.nwmovie.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import app.nwmovie.BuildConfig
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Anti-tamper check. The app compares the certificate it was signed with against the fingerprint built into it
 * (APP_CERT_SHA256). A copy that was changed and re-signed by someone else has a different certificate, so the app stops.
 * The check runs at several places (start, opening the app, playing, updating). No app can be made unbreakable,
 * but this stops casual re-packaging and removal of the update system.
 */
object Integrity {
    fun verify(ctx: Context) {
        val expected = BuildConfig.CERT_SHA256.replace(":", "").trim().lowercase()
        if (expected.isEmpty()) return                                        // not configured for this build
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable || fingerprint(ctx) != expected) die()
    }

    /** SHA-256 fingerprint of the certificate this app is signed with (shown in Settings > App updates for the one-time setup). */
    fun fingerprint(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    } catch (e: Exception) { null }

    private fun die(): Nothing {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}

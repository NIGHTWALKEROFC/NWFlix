package app.nwmovie.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import app.nwmovie.BuildConfig
import java.security.MessageDigest
import kotlin.system.exitProcess

object Integrity {
    fun verify(ctx: Context) {
        val expected = BuildConfig.CERT_SHA256.replace(":", "").trim().lowercase()
        if (expected.isEmpty()) return
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable || certHash(ctx) != expected) die()
    }

    private fun certHash(ctx: Context): String? = try {
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

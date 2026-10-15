package app.nwmovie.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Locale

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun fmtSize(b: Long): String {
    if (b <= 0) return "0 B"
    val u = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i <= 1) String.format(Locale.US, "%.0f %s", v, u[i]) else String.format(Locale.US, "%.1f %s", v, u[i])
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun fmtRuntime(min: Int): String = if (min <= 0) "" else "${min / 60}h ${min % 60}m"

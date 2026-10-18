package app.nwmovie.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import app.nwmovie.BuildConfig
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * In-app updates. update.json (and update.json.sig, an RSA signature of it) live on your website.
 * The app only trusts an update.json signed with YOUR private key (the public key is built into the app),
 * downloads the APK that fits this phone's CPU, checks its SHA-256, and installs it with the system installer.
 */
object Updater {
    class Info(val versionCode: Int, val versionName: String, val notes: String, val mandatory: Boolean, val url: String, val sha256: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data object NotConfigured : State
        data class Available(val info: Info) : State
        data class NeedPermission(val info: Info) : State
        data class Downloading(val info: Info, val percent: Int) : State
        data class Installing(val info: Info) : State
        data class Failed(val message: String, val info: Info? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    const val ACTION_RESULT = "app.nwmovie.INSTALL_RESULT"
    private const val DAY = 24L * 3600_000

    val configured: Boolean get() = BuildConfig.UPDATE_URL.startsWith("https://") && BuildConfig.UPDATE_PUBKEY.isNotBlank()

    private fun fetch(url: String): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 12_000; readTimeout = 20_000; instanceFollowRedirects = true }
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun verifySignature(data: ByteArray, sigB64: String): Boolean = try {
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(BuildConfig.UPDATE_PUBKEY.trim(), Base64.DEFAULT)))
        Signature.getInstance("SHA256withRSA").run { initVerify(pub); update(data); verify(Base64.decode(sigB64.trim(), Base64.DEFAULT)) }
    } catch (e: Exception) { false }

    /** manual = user pressed "Check for updates": ignores the "later" snooze and shows the result. */
    suspend fun check(ctx: Context, manual: Boolean) {
        Integrity.verify(ctx)
        if (!configured) { state.value = State.NotConfigured; return }
        val p = ServiceLocator.prefs.value
        val now = System.currentTimeMillis()
        if (!manual && (now < p.updateSnoozeUntil || now - p.lastUpdateCheck < 6 * 3600_000L)) return
        if (state.value is State.Downloading || state.value is State.Installing) return
        state.value = State.Checking
        try {
            val info = withContext(Dispatchers.IO) {
                val json = fetch(BuildConfig.UPDATE_URL)
                val sig = String(fetch(BuildConfig.UPDATE_URL + ".sig"))
                if (!verifySignature(json, sig)) throw SecurityException("The update information is not signed by the developer.")
                val o = JSONObject(String(json))
                val code = o.getInt("versionCode")
                val apks = o.getJSONObject("apks")
                val abi = Build.SUPPORTED_ABIS.firstOrNull { apks.has(it) } ?: "universal"
                val a = apks.optJSONObject(abi) ?: throw IllegalStateException("No APK for this phone")
                val url = a.getString("url")
                if (!url.startsWith("https://")) throw SecurityException("Insecure download link")
                Info(code, o.optString("versionName"), o.optString("notes"),
                    o.optBoolean("mandatory", false) || BuildConfig.VERSION_CODE < o.optInt("minVersionCode", 0),
                    url, a.getString("sha256").lowercase(), a.optLong("size", 0))
            }
            ServiceLocator.settings.set(K.lastUpdateCheck, now)
            state.value = if (info.versionCode > BuildConfig.VERSION_CODE) State.Available(info) else State.UpToDate
        } catch (e: Exception) {
            state.value = if (manual) State.Failed("Couldn't check for updates: ${e.message}") else State.Idle
        }
    }

    /** "Later": hide the question until tomorrow. */
    suspend fun later() {
        ServiceLocator.settings.set(K.updateSnoozeUntil, System.currentTimeMillis() + DAY)
        state.value = State.Idle
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionScreen(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    /** Called after the user returns from the "allow installs" screen. */
    suspend fun continueIfPermitted(ctx: Context) {
        val s = state.value
        if (s is State.NeedPermission && canInstall(ctx)) downloadAndInstall(ctx, s.info)
    }

    suspend fun downloadAndInstall(ctx: Context, info: Info) {
        Integrity.verify(ctx)
        if (!canInstall(ctx)) { state.value = State.NeedPermission(info); return }
        try {
            state.value = State.Downloading(info, 0)
            val file = withContext(Dispatchers.IO) { download(ctx, info) }
            state.value = State.Installing(info)
            withContext(Dispatchers.IO) { install(ctx, file) }
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: "Update failed", info)
        }
    }

    private fun download(ctx: Context, info: Info): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "nwmovie-update.apk")
        val c = (URL(info.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            c.inputStream.use { i -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); md.update(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; state.value = State.Downloading(info, pct) }
                }
            } }
        } finally { c.disconnect() }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (hash != info.sha256) { out.delete(); throw SecurityException("The downloaded file does not match the signed checksum. Update cancelled.") }
        return out
    }

    private fun install(ctx: Context, file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)   // updates silently when Android allows it
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            file.inputStream().use { i -> s.openWrite("nwmovie", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
        }
    }
}

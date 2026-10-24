package app.nwmovie.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.nwmovie.BuildConfig
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object Updater {
    data class Info(val versionCode: Int, val versionName: String, val notes: String, val force: Boolean, val url: String, val sha256: String = "")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data object NotConfigured : State
        data class Available(val info: Info) : State
        data class NeedPermission(val info: Info) : State
        data class Downloading(val info: Info, val percent: Int) : State
        data class Installing(val info: Info) : State
        data class Ready(val info: Info, val file: File, val useSystemInstaller: Boolean, val note: String = "") : State
        data class Failed(val message: String, val info: Info? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val hidden = MutableStateFlow(false)
    @Volatile var pending: Pair<Info, File>? = null
    @Volatile private var job: Job? = null

    const val ACTION_RESULT = "app.nwmovie.INSTALL_RESULT"
    const val READY_NOTICE_ID = 1003
    private const val DAY = 24L * 3600_000
    private const val MAX_APK = 800L * 1024 * 1024
    private const val FILE_NAME = "nwflix-update.apk"

    fun infoOf(s: State): Info? = when (s) {
        is State.Available -> s.info
        is State.NeedPermission -> s.info
        is State.Downloading -> s.info
        is State.Installing -> s.info
        is State.Ready -> s.info
        is State.Failed -> s.info
        else -> null
    }

    fun isBlocking(s: State): Boolean = infoOf(s)?.force == true

    fun visible(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun set(s: State) {
        if (s !is State.Downloading) hidden.value = false
        state.value = s
    }

    private fun prefs() = ServiceLocator.app.getSharedPreferences("nw_update", Context.MODE_PRIVATE)

    fun start() {
        File(ServiceLocator.app.cacheDir, "updates").deleteRecursively()
        ServiceLocator.scope.launch { Telemetry.remote.collect { evaluate(it, false) } }
    }

    private fun infoFrom(r: Telemetry.Remote): Info? {
        if (r.updCode <= 0) return null
        val links = mapOf(
            "arm64-v8a" to (r.updArm64 to r.updSha64), "armeabi-v7a" to (r.updArm32 to r.updSha32),
            "armeabi" to (r.updArm32 to r.updSha32), "x86_64" to (r.updX86 to r.updShaX86)
        )
        val pick = Build.SUPPORTED_ABIS.asSequence().mapNotNull { links[it] }.firstOrNull { it.first.startsWith("https://") } ?: return null
        return Info(r.updCode, r.updName.ifBlank { r.updCode.toString() }, r.updNotes, r.updForce, pick.first, pick.second)
    }

    private fun evaluate(r: Telemetry.Remote, manual: Boolean) {
        val s = state.value
        if (manual) {
            if (s is State.Downloading || s is State.Installing || s is State.Ready || s is State.NeedPermission) return
        } else if (s !is State.Idle && s !is State.UpToDate && s !is State.Available) return
        val info = infoFrom(r)
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            if (manual) {
                set(
                    if (r.updCode > BuildConfig.VERSION_CODE) State.Failed("A new version was announced, but its download isn't ready for this phone yet. Please try again later.")
                    else State.UpToDate
                )
            } else if (s is State.Available) set(State.Idle)
            return
        }
        val app = ServiceLocator.app
        if (!manual && prefs().getBoolean("waiting", false) && canInstall(app) && visible()) {
            prefs().edit().putBoolean("waiting", false).apply()
            run(app, info)
            return
        }
        val snoozed = !manual && !info.force && System.currentTimeMillis() < ServiceLocator.prefs.value.updateSnoozeUntil &&
            info.versionCode <= prefs().getInt("snoozed_code", 0)
        set(if (snoozed) State.Idle else State.Available(info))
    }

    suspend fun check(ctx: Context, manual: Boolean) {
        Integrity.verify(ctx)
        if (!Telemetry.configured) { if (manual) set(State.NotConfigured); return }
        if (!manual) { evaluate(Telemetry.remote.value, false); return }
        val s = state.value
        if (s is State.Downloading || s is State.Installing || s is State.Ready || s is State.NeedPermission) return
        set(State.Checking)
        if (!Telemetry.fetchNow()) {
            set(State.Failed("Couldn't check for updates. Check your internet connection and try again."))
            return
        }
        evaluate(Telemetry.remote.value, true)
    }

    suspend fun later() {
        infoOf(state.value)?.let { prefs().edit().putInt("snoozed_code", it.versionCode).apply() }
        ServiceLocator.settings.set(K.updateSnoozeUntil, System.currentTimeMillis() + DAY)
        prefs().edit().putBoolean("waiting", false).apply()
        set(State.Idle)
    }

    fun dismiss() {
        prefs().edit().putBoolean("waiting", false).apply()
        set(State.Idle)
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionScreen(ctx: Context) {
        prefs().edit().putBoolean("waiting", true).apply()
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    fun begin(ctx: Context, info: Info) {
        Integrity.verify(ctx)
        hidden.value = false
        if (!canInstall(ctx)) { set(State.NeedPermission(info)); return }
        run(ctx.applicationContext, info)
    }

    fun continueIfPermitted(ctx: Context) {
        val s = state.value
        if (s is State.NeedPermission && canInstall(ctx)) {
            prefs().edit().putBoolean("waiting", false).apply()
            run(ctx.applicationContext, s.info)
        }
    }

    private fun run(ctx: Context, info: Info) {
        if (job?.isActive == true) return
        clearNotice(ctx)
        set(State.Downloading(info, 0))
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, UpdateService::class.java)) } catch (_: Exception) { }
        job = ServiceLocator.scope.launch(Dispatchers.IO) {
            try {
                val file = download(ctx, info)
                val problem = verify(ctx, file, info)
                if (problem != null) { file.delete(); set(State.Failed(problem, info)); return@launch }
                installSession(ctx, info, file)
            } catch (e: Exception) {
                set(State.Failed(e.message ?: "The download failed. Please try again.", info))
            }
        }
    }

    private fun download(ctx: Context, info: Info): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val part = File(dir, "$FILE_NAME.part")
        val out = File(dir, FILE_NAME)
        val c = (URL(info.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            if (c.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${c.responseCode}). Please check the update link.")
            if (c.url.protocol != "https") throw SecurityException("The update link is not secure. Update cancelled.")
            val total = c.contentLengthLong
            if (total > MAX_APK) throw SecurityException("The update file is too large. Update cancelled.")
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -2
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (done > MAX_APK) throw SecurityException("The update file is too large. Update cancelled.")
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != last) { last = pct; set(State.Downloading(info, pct)) }
                }
                if (total > 0 && done != total) throw IllegalStateException("The download was cut short. Please try again.")
            } }
            if (info.sha256.isNotEmpty() && md.digest().joinToString("") { "%02x".format(it) } != info.sha256) {
                throw SecurityException("The downloaded file does not match the checksum set by the developer. Update cancelled.")
            }
            if (!part.renameTo(out)) throw IllegalStateException("Couldn't save the update file.")
        } catch (e: Exception) {
            part.delete(); out.delete(); throw e
        } finally { c.disconnect() }
        return out
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun certs(p: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") p.signatures
        }
        return sigs?.map { sha(it.toByteArray()) }?.toSet().orEmpty()
    }

    private fun verify(ctx: Context, file: File, info: Info): String? {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pkg = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "The downloaded file is not a valid app. Please check the update link."
        if (pkg.packageName != ctx.packageName) return "The downloaded file is not NWFlix. Please check the update link."
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) return "The downloaded file is not a newer version. Please check the update link."
        val mine = certs(pm.getPackageInfo(ctx.packageName, flags))
        val theirs = certs(pkg)
        if (mine.isEmpty() || theirs.isEmpty() || mine.intersect(theirs).isEmpty()) return "The downloaded file is not signed by the developer. Update cancelled."
        return null
    }

    fun install(ctx: Context, s: State.Ready) {
        if (s.useSystemInstaller) installWithSystem(ctx, s.info, s.file) else ServiceLocator.scope.launch(Dispatchers.IO) { installSession(ctx.applicationContext, s.info, s.file) }
    }

    class News(val name: String, val notes: String)

    fun pendingNews(): News? {
        val p = prefs()
        val code = p.getInt("news_code", 0)
        val notes = p.getString("news_notes", "").orEmpty()
        if (code <= 0 || notes.isBlank() || BuildConfig.VERSION_CODE < code) return null
        return News(p.getString("news_name", "").orEmpty().ifBlank { BuildConfig.VERSION_NAME }, notes)
    }

    class Welcome(val firstTime: Boolean, val version: String, val notes: String)

    fun pendingWelcome(): Welcome? {
        val seen = prefs().getInt("seen_code", 0)
        val cur = BuildConfig.VERSION_CODE
        if (seen in 1 until cur || seen == 0) {
            val first = seen == 0 && try {
                val pi = ServiceLocator.app.packageManager.getPackageInfo(ServiceLocator.app.packageName, 0)
                Math.abs(pi.lastUpdateTime - pi.firstInstallTime) < 5000
            } catch (_: Exception) { false }
            return Welcome(first, BuildConfig.VERSION_NAME, if (first) "" else pendingNews()?.notes.orEmpty())
        }
        return null
    }

    fun welcomeSeen() {
        prefs().edit().putInt("seen_code", BuildConfig.VERSION_CODE).apply()
        newsSeen()
    }

    fun newsSeen() {
        prefs().edit().remove("news_code").remove("news_name").remove("news_notes").apply()
    }

    private fun installSession(ctx: Context, info: Info, file: File) {
        pending = info to file
        if (info.notes.isNotBlank()) prefs().edit().putInt("news_code", info.versionCode).putString("news_name", info.versionName).putString("news_notes", info.notes).apply()
        clearNotice(ctx)
        set(State.Installing(info))
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sid = pi.createSession(params)
            pi.openSession(sid).use { s ->
                file.inputStream().use { i -> s.openWrite("nwflix", 0, file.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_RESULT).setPackage(ctx.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            set(State.Ready(info, file, true, "Automatic install isn't supported on this phone. Tap Install to continue."))
        }
    }

    private fun installWithSystem(ctx: Context, info: Info, file: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            set(State.Failed("Couldn't open the installer. Please try again.", info))
        }
    }

    fun onInstallResult(ctx: Context, status: Int, message: String?) {
        val p = pending
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> { p?.second?.delete(); pending = null }
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                if (p != null) set(State.Ready(p.first, p.second, false, "The installation was cancelled. Tap Install to try again."))
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_BLOCKED ->
                if (p != null) set(State.Ready(p.first, p.second, true, "Automatic install isn't supported on this phone. Tap Install to continue."))
            else -> set(State.Failed(message ?: "The update was not installed", p?.first))
        }
    }

    fun markReady(info: Info?, file: File?) {
        if (info != null && file != null) set(State.Ready(info, file, false))
    }

    fun clearNotice(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(READY_NOTICE_ID)
    }
}

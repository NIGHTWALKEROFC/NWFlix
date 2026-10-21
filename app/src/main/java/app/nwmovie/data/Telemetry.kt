package app.nwmovie.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.nwmovie.BuildConfig
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Anonymous usage counting and the maintenance / announcement notice, using Firebase's free REST interface.
 * Nothing is sent unless the Firebase project id and key were added to the build, and the statistics part can be
 * switched off by the user.
 */
object Telemetry {
    data class Remote(
        val maintenanceOn: Boolean = false, val maintenanceTitle: String = "", val maintenanceMessage: String = "",
        val annId: String = "", val annTitle: String = "", val annMessage: String = "",
        val annButtonText: String = "", val annButtonUrl: String = "", val annDismissible: Boolean = true,
        val annExpiresAt: Long = 0L, val annMinVersion: Int = 0, val annMaxVersion: Int = 0, val annCountry: String = ""
    )

    private class HttpErr(val code: Int, msg: String) : IOException(msg)

    val remote = MutableStateFlow(Remote())

    private val pid get() = BuildConfig.FB_PROJECT_ID
    private val key get() = BuildConfig.FB_API_KEY
    val configured get() = pid.isNotBlank() && key.isNotBlank()

    private var sp: SharedPreferences? = null
    private var started = false
    private var countedOpen = false
    @Volatile private var lastRun = 0L
    private val lock = Mutex()

    fun init(c: Context, scope: CoroutineScope) {
        if (!configured || started) return
        started = true
        sp = c.applicationContext.getSharedPreferences("nw_stats", Context.MODE_PRIVATE)
        scope.launch(Dispatchers.IO) {
            delay(3000)
            while (true) {
                if (foreground()) runOnce()
                delay(10 * 60_000L)
            }
        }
    }

    /** Called when the app comes back to the screen. */
    fun poke(scope: CoroutineScope) {
        if (configured && started && System.currentTimeMillis() - lastRun > 2 * 60_000L) scope.launch(Dispatchers.IO) { runOnce() }
    }

    /** "Try again" on the maintenance screen. */
    suspend fun refresh() = withContext(Dispatchers.IO) { try { fetchConfig() } catch (_: Exception) { } }

    private fun foreground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private suspend fun runOnce() {
        lock.withLock {
            lastRun = System.currentTimeMillis()
            try { fetchConfig() } catch (_: Exception) { }
            if (ServiceLocator.prefs.value.shareStats) try { heartbeat() } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ notice

    private fun fetchConfig() {
        val r = try {
            http("GET", "https://firestore.googleapis.com/v1/projects/$pid/databases/(default)/documents/config/app?key=$key")
        } catch (e: HttpErr) {
            if (e.code == 404) { remote.value = Remote(); return }
            throw e
        }
        val f = r.optJSONObject("fields") ?: run { remote.value = Remote(); return }
        fun str(n: String) = f.optJSONObject(n)?.optString("stringValue").orEmpty()
        fun bool(n: String, d: Boolean = false) = f.optJSONObject(n)?.let { if (it.has("booleanValue")) it.optBoolean("booleanValue") else d } ?: d
        fun int(n: String) = f.optJSONObject(n)?.optString("integerValue")?.toIntOrNull() ?: 0
        fun ts(n: String) = parseTs(f.optJSONObject(n)?.optString("timestampValue"))
        remote.value = Remote(
            maintenanceOn = bool("maintenanceOn"), maintenanceTitle = str("maintenanceTitle"), maintenanceMessage = str("maintenanceMessage"),
            annId = str("annId"), annTitle = str("annTitle"), annMessage = str("annMessage"),
            annButtonText = str("annButtonText"), annButtonUrl = str("annButtonUrl"), annDismissible = bool("annDismissible", true),
            annExpiresAt = ts("annExpiresAt"), annMinVersion = int("annMinVersion"), annMaxVersion = int("annMaxVersion"), annCountry = str("annCountry")
        )
    }

    // ------------------------------------------------------------ anonymous record

    private fun heartbeat() {
        val s = sp ?: return
        val (uid, token) = signIn() ?: return
        val now = System.currentTimeMillis()
        val fields = JSONObject()
        val mask = JSONArray()
        fun put(n: String, v: JSONObject) { fields.put(n, v); mask.put(n) }
        fun str(v: String) = JSONObject().put("stringValue", v)
        fun num(v: Int) = JSONObject().put("integerValue", v.toString())
        fun time(v: Long) = JSONObject().put("timestampValue", iso(v))
        put("v", str(BuildConfig.VERSION_NAME))
        put("vc", num(BuildConfig.VERSION_CODE))
        put("os", str(Build.VERSION.RELEASE ?: ""))
        put("sdk", num(Build.VERSION.SDK_INT))
        put("model", str("${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)))
        put("country", str(Locale.getDefault().country.take(2)))
        put("lang", str(Locale.getDefault().language.take(3)))
        put("lastSeen", time(now))
        val joined = s.getBoolean("joined", false)
        if (!joined) put("firstSeen", time(now))
        val write = JSONObject()
            .put("update", JSONObject().put("name", "projects/$pid/databases/(default)/documents/users/$uid").put("fields", fields))
            .put("updateMask", JSONObject().put("fieldPaths", mask))
        if (!countedOpen) write.put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "opens").put("increment", JSONObject().put("integerValue", "1"))))
        http(
            "POST", "https://firestore.googleapis.com/v1/projects/$pid/databases/(default)/documents:commit",
            JSONObject().put("writes", JSONArray().put(write)).toString(), bearer = token
        )
        countedOpen = true
        if (!joined) s.edit().putBoolean("joined", true).apply()
    }

    /** An anonymous Firebase account: no email, no name. Returns its id and a current token. */
    private fun signIn(): Pair<String, String>? {
        val s = sp ?: return null
        val uid = s.getString("uid", null); val tok = s.getString("id", null)
        if (uid != null && tok != null && System.currentTimeMillis() < s.getLong("exp", 0) - 60_000) return uid to tok
        val refresh = s.getString("refresh", null)
        if (refresh != null) {
            try {
                val r = http("POST", "https://securetoken.googleapis.com/v1/token?key=$key",
                    "grant_type=refresh_token&refresh_token=$refresh", "application/x-www-form-urlencoded")
                save(r.getString("user_id"), r.getString("id_token"), r.getString("refresh_token"), r.getString("expires_in").toLong())
                return r.getString("user_id") to r.getString("id_token")
            } catch (_: Exception) { s.edit().remove("refresh").remove("id").putBoolean("joined", false).apply() }
        }
        val r = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key", """{"returnSecureToken":true}""")
        save(r.getString("localId"), r.getString("idToken"), r.getString("refreshToken"), r.getString("expiresIn").toLong())
        return r.getString("localId") to r.getString("idToken")
    }

    private fun save(uid: String, id: String, refresh: String, expiresInSec: Long) {
        sp?.edit()?.putString("uid", uid)?.putString("id", id)?.putString("refresh", refresh)
            ?.putLong("exp", System.currentTimeMillis() + expiresInSec * 1000)?.apply()
    }

    // ------------------------------------------------------------ helpers

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun parseTs(s: String?): Long {
        if (s.isNullOrBlank() || s.length < 19) return 0L
        return try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time ?: 0L } catch (e: Exception) { 0L }
    }

    private fun http(method: String, url: String, body: String? = null, type: String = "application/json", bearer: String? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpErr(code, "HTTP $code")
            return JSONObject(text.ifBlank { "{}" })
        } finally { c.disconnect() }
    }
}

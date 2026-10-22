package app.nwmovie.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("nw_settings")

data class Prefs(
    // playback
    val speed: Float = 1f, val audioLang: String = "", val subLang: String = "", val subSize: Float = 20f,
    val subBg: Boolean = true, val subColor: Int = 0xFFFFFFFF.toInt(), val subDelayMs: Int = 0, val audioDelayMs: Int = 0,
    
    val gestures: Boolean = true, val brightnessGesture: Boolean = true, val volumeGesture: Boolean = true,
    val seekGesture: Boolean = true, val rememberBrightness: Boolean = true, val rememberVolume: Boolean = true, val doubleTapSec: Int = 10,
    val autoNext: Boolean = true, val resume: Boolean = true, val hwAccel: Boolean = true,
    val autoQuality: Boolean = true, val maxQuality: Int = 0, val bgPlayback: Boolean = false,
    // network
    val wifiOnlyStream: Boolean = false, val wifiOnlyDownload: Boolean = true, val mobileData: Boolean = true,
    val bandwidthSaver: Boolean = false, val bgDownloads: Boolean = false,
    // privacy
    val appLock: Boolean = false, val hideNotif: Boolean = false, val blockShots: Boolean = false,
    // cache
    val posterCacheMb: Int = 300, val tempCacheMb: Int = 2048, val autoClean: Boolean = true, val lastOpen: Long = 0L,
    // appearance
    val theme: String = "dark", val accent: Int = 0, val fontScale: Float = 1f, val showTitles: Boolean = true,
    val fullscreen: Boolean = true,
    val savedBrightness: Float = -1f, val savedVolume: Int = -1,
    val smartQuality: Boolean = true, val dlOnlyCharging: Boolean = false, val dlOnlyNight: Boolean = false,
    val clearAfterWatch: String = "auto", val cacheDays: Int = 3, val onboarded: Boolean = false,
    val uiLang: String = "en", val dirBackfill: Boolean = false,
    val searchHistory: String = "",
    val engine: String = "auto", val updateSnoozeUntil: Long = 0L, val lastUpdateCheck: Long = 0L,
    val scanVersion: Int = 0, val previews: Boolean = true, val previewMobile: Boolean = true, val shareStats: Boolean = true, val annSeen: String = "", val hideUnmatched: Boolean = true, val forceUpdateCode: Int = 0
)

object K {
    val speed = floatPreferencesKey("speed"); val audioLang = stringPreferencesKey("audioLang")
    val subLang = stringPreferencesKey("subLang"); val subSize = floatPreferencesKey("subSize")
    val subBg = booleanPreferencesKey("subBg"); val subColor = intPreferencesKey("subColor")
    val subDelayMs = intPreferencesKey("subDelayMs"); val audioDelayMs = intPreferencesKey("audioDelayMs")
    val gestures = booleanPreferencesKey("gestures"); val brightnessGesture = booleanPreferencesKey("brightnessGesture")
    val volumeGesture = booleanPreferencesKey("volumeGesture"); val seekGesture = booleanPreferencesKey("seekGesture")
    val rememberBrightness = booleanPreferencesKey("rememberBrightness"); val rememberVolume = booleanPreferencesKey("rememberVolume")
    val savedVolume = intPreferencesKey("savedVolume"); val doubleTapSec = intPreferencesKey("doubleTapSec")
    val autoNext = booleanPreferencesKey("autoNext"); val resume = booleanPreferencesKey("resume")
    val hwAccel = booleanPreferencesKey("hwAccel"); val autoQuality = booleanPreferencesKey("autoQuality")
    val maxQuality = intPreferencesKey("maxQuality"); val bgPlayback = booleanPreferencesKey("bgPlayback")
    val wifiOnlyStream = booleanPreferencesKey("wifiOnlyStream"); val wifiOnlyDownload = booleanPreferencesKey("wifiOnlyDownload")
    val mobileData = booleanPreferencesKey("mobileData"); val bandwidthSaver = booleanPreferencesKey("bandwidthSaver")
    val bgDownloads = booleanPreferencesKey("bgDownloads")
    val appLock = booleanPreferencesKey("appLock"); val hideNotif = booleanPreferencesKey("hideNotif")
    val blockShots = booleanPreferencesKey("blockShots")
    val posterCacheMb = intPreferencesKey("posterCacheMb"); val tempCacheMb = intPreferencesKey("tempCacheMb")
    val autoClean = booleanPreferencesKey("autoClean"); val lastOpen = longPreferencesKey("lastOpen")
    val theme = stringPreferencesKey("theme"); val accent = intPreferencesKey("accent")
    val fontScale = floatPreferencesKey("fontScale"); val showTitles = booleanPreferencesKey("showTitles")
    val fullscreen = booleanPreferencesKey("fullscreen"); val savedBrightness = floatPreferencesKey("savedBrightness")
    val smartQuality = booleanPreferencesKey("smartQuality"); val dlOnlyCharging = booleanPreferencesKey("dlOnlyCharging")
    val dlOnlyNight = booleanPreferencesKey("dlOnlyNight"); val clearAfterWatch = stringPreferencesKey("clearAfterWatch")
    val cacheDays = intPreferencesKey("cacheDays"); val onboarded = booleanPreferencesKey("onboarded")
    val uiLang = stringPreferencesKey("uiLang"); val dirBackfill = booleanPreferencesKey("dirBackfill")
    val searchHistory = stringPreferencesKey("searchHistory")
    val scanVersion = intPreferencesKey("scanVersion"); val engine = stringPreferencesKey("engine"); val updateSnoozeUntil = longPreferencesKey("updateSnoozeUntil"); val lastUpdateCheck = longPreferencesKey("lastUpdateCheck")
    val previews = booleanPreferencesKey("previews"); val previewMobile = booleanPreferencesKey("previewMobile"); val shareStats = booleanPreferencesKey("shareStats"); val annSeen = stringPreferencesKey("annSeen"); val hideUnmatched = booleanPreferencesKey("hideUnmatched"); val forceUpdateCode = intPreferencesKey("forceUpdateCode")
}

class SettingsStore(ctx: Context) {
    private val ds = ctx.dataStore

    val flow: Flow<Prefs> = ds.data.map { p ->
        val d = Prefs()
        Prefs(
            speed = p[K.speed] ?: d.speed, audioLang = p[K.audioLang] ?: d.audioLang, subLang = p[K.subLang] ?: d.subLang,
            subSize = p[K.subSize] ?: d.subSize, subBg = p[K.subBg] ?: d.subBg, subColor = p[K.subColor] ?: d.subColor,
            subDelayMs = p[K.subDelayMs] ?: d.subDelayMs, audioDelayMs = p[K.audioDelayMs] ?: d.audioDelayMs,
            gestures = p[K.gestures] ?: d.gestures, brightnessGesture = p[K.brightnessGesture] ?: d.brightnessGesture,
            volumeGesture = p[K.volumeGesture] ?: d.volumeGesture, seekGesture = p[K.seekGesture] ?: d.seekGesture,
            rememberBrightness = p[K.rememberBrightness] ?: d.rememberBrightness, rememberVolume = p[K.rememberVolume] ?: d.rememberVolume, doubleTapSec = p[K.doubleTapSec] ?: d.doubleTapSec,
            autoNext = p[K.autoNext] ?: d.autoNext, resume = p[K.resume] ?: d.resume, hwAccel = p[K.hwAccel] ?: d.hwAccel,
            autoQuality = p[K.autoQuality] ?: d.autoQuality, maxQuality = p[K.maxQuality] ?: d.maxQuality,
            bgPlayback = p[K.bgPlayback] ?: d.bgPlayback,
            wifiOnlyStream = p[K.wifiOnlyStream] ?: d.wifiOnlyStream, wifiOnlyDownload = p[K.wifiOnlyDownload] ?: d.wifiOnlyDownload,
            mobileData = p[K.mobileData] ?: d.mobileData, bandwidthSaver = p[K.bandwidthSaver] ?: d.bandwidthSaver,
            bgDownloads = p[K.bgDownloads] ?: d.bgDownloads,
            appLock = p[K.appLock] ?: d.appLock, hideNotif = p[K.hideNotif] ?: d.hideNotif, blockShots = p[K.blockShots] ?: d.blockShots,
            posterCacheMb = p[K.posterCacheMb] ?: d.posterCacheMb, tempCacheMb = p[K.tempCacheMb] ?: d.tempCacheMb,
            autoClean = p[K.autoClean] ?: d.autoClean, lastOpen = p[K.lastOpen] ?: d.lastOpen,
            theme = p[K.theme] ?: d.theme, accent = p[K.accent] ?: d.accent, fontScale = p[K.fontScale] ?: d.fontScale,
            showTitles = p[K.showTitles] ?: d.showTitles, fullscreen = p[K.fullscreen] ?: d.fullscreen,
            savedBrightness = p[K.savedBrightness] ?: d.savedBrightness, savedVolume = p[K.savedVolume] ?: d.savedVolume,
            smartQuality = p[K.smartQuality] ?: d.smartQuality, dlOnlyCharging = p[K.dlOnlyCharging] ?: d.dlOnlyCharging,
            dlOnlyNight = p[K.dlOnlyNight] ?: d.dlOnlyNight, clearAfterWatch = p[K.clearAfterWatch] ?: d.clearAfterWatch,
            cacheDays = p[K.cacheDays] ?: d.cacheDays, onboarded = p[K.onboarded] ?: d.onboarded,
            uiLang = p[K.uiLang] ?: d.uiLang, dirBackfill = p[K.dirBackfill] ?: d.dirBackfill,
            searchHistory = p[K.searchHistory] ?: d.searchHistory,
            engine = p[K.engine] ?: d.engine, updateSnoozeUntil = p[K.updateSnoozeUntil] ?: d.updateSnoozeUntil, lastUpdateCheck = p[K.lastUpdateCheck] ?: d.lastUpdateCheck,
            scanVersion = p[K.scanVersion] ?: d.scanVersion, previews = p[K.previews] ?: d.previews, previewMobile = p[K.previewMobile] ?: d.previewMobile, shareStats = p[K.shareStats] ?: d.shareStats, annSeen = p[K.annSeen] ?: d.annSeen, hideUnmatched = p[K.hideUnmatched] ?: d.hideUnmatched,
            forceUpdateCode = p[K.forceUpdateCode] ?: d.forceUpdateCode
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ds.edit { it[key] = value } }
}

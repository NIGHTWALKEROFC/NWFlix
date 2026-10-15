package app.nwmovie

import android.app.Application
import androidx.room.Room
import app.nwmovie.data.*
import app.nwmovie.data.db.AppDb
import app.nwmovie.data.telegram.Tg
import app.nwmovie.data.tmdb.TmdbClient
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** One place that builds the app's long-lived objects. */
object ServiceLocator {
    lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsStore
    lateinit var prefs: StateFlow<Prefs>
    lateinit var db: AppDb
    lateinit var tg: Tg
    lateinit var tmdb: TmdbClient
    lateinit var indexer: Indexer
    lateinit var downloads: DownloadsManager
    lateinit var images: ImageLoader

    fun init(a: Application) {
        app = a
        Secrets.init(a)
        settings = SettingsStore(a)
        prefs = settings.flow.stateIn(scope, SharingStarted.Eagerly, runBlockingPrefs())
        db = Room.databaseBuilder(a, AppDb::class.java, "nw.db").fallbackToDestructiveMigration().build()
        tg = Tg(a, scope)
        tmdb = TmdbClient(a)
        indexer = Indexer(tg, tmdb, db)
        downloads = DownloadsManager(a, scope)
        images = ImageLoader.Builder(a)
            .memoryCache { MemoryCache.Builder(a).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder().directory(File(a.cacheDir, "posters"))
                    .maxSizeBytes(runBlockingPrefs().posterCacheMb * 1024L * 1024L).build()
            }
            .crossfade(true)
            .build()
        NetPolicy.init(a)
        downloads.init()
    }

    private fun runBlockingPrefs(): Prefs = try {
        runBlocking { settings.flow.first() }
    } catch (e: Exception) { Prefs() }

    /** Used by "Clear session": removes everything stored locally except your settings. */
    suspend fun wipeLocalData() {
        downloads.deleteAll()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        CacheManager.clearPosters()
        CacheManager.clearMetadata()
    }
}

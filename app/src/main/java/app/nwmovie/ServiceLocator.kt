package app.nwmovie

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.nwmovie.data.*
import app.nwmovie.data.db.AppDb
import app.nwmovie.data.Telemetry
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
    /** When the app was opened the time before this one (used for the 3-day inactivity cleanup). */
    @Volatile var prevOpen: Long = 0L

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (col in listOf("season", "episode", "isSeries", "part", "watchedMs"))
                db.execSQL("ALTER TABLE movies ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE movies ADD COLUMN caption TEXT NOT NULL DEFAULT ''")
        }
    }

    fun init(a: Application) {
        app = a
        Secrets.init(a)
        settings = SettingsStore(a)
        prefs = settings.flow.stateIn(scope, SharingStarted.Eagerly, runBlockingPrefs())
        prevOpen = prefs.value.lastOpen
        db = Room.databaseBuilder(a, AppDb::class.java, "nw.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).fallbackToDestructiveMigration().build()
        tg = Tg(a, scope)
        tmdb = TmdbClient(a)
        indexer = Indexer(tg, tmdb, db)
        downloads = DownloadsManager(a, scope)
        images = ImageLoader.Builder(a)
            .memoryCache { MemoryCache.Builder(a).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder().directory(File(a.filesDir, "posters"))
                    .maxSizeBytes(runBlockingPrefs().posterCacheMb * 1024L * 1024L).build()
            }
            .crossfade(true)
            .build()
        NetPolicy.init(a)
        downloads.init()
        Telemetry.init(a, scope)

        // The app only works while it is open: when it is hidden (and nothing is playing or downloading) Telegram's network is paused.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { scope.launch { tg.setConnected(true) }; Telemetry.poke(scope) }
            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    delay(4000)
                    // a running scan, a playing movie or a download keeps Telegram connected until it is finished
                    while (true) {
                        val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        if (visible) break
                        if (!AppState.playerPlaying && !downloads.hasActive() && !indexer.state.value.running) { tg.setConnected(false); break }
                        delay(5000)
                    }
                }
            }
        })
    }

    private fun runBlockingPrefs(): Prefs = try {
        runBlocking { settings.flow.first() }
    } catch (e: Exception) { Prefs() }

    /** Used by "Clear session": removes everything stored locally except your settings. */
    suspend fun wipeLocalData() {
        downloads.deleteAll()
        ScanStore.clear()
        withContext(Dispatchers.IO) { db.clearAllTables() }
        CacheManager.clearPosters()
        CacheManager.clearMetadata()
    }
}

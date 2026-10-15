package app.nwmovie.data

import android.content.Context
import app.nwmovie.ServiceLocator
import java.io.File

/**
 * Four separate categories, plus the Downloads folder which is never touched by cache clearing:
 *  1. Posters   – image cache (cache/posters)
 *  2. Metadata  – TMDB JSON cache (cache/tmdb)
 *  3. Index     – the local movie list (Room database)
 *  4. Temporary – TDLib streaming/partial files (noBackup/td/files)
 */
object CacheManager {
    data class Usage(val posters: Long, val metadata: Long, val index: Long, val temp: Long, val downloads: Long) {
        val cacheTotal get() = posters + metadata + index + temp
    }

    fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun usage(ctx: Context): Usage {
        val sl = ServiceLocator
        val dbFile = ctx.getDatabasePath("nw.db")
        val index = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
        return Usage(
            posters = dirSize(File(ctx.cacheDir, "posters")),
            metadata = dirSize(sl.tmdb.dir),
            index = index,
            temp = dirSize(sl.tg.tempDir),
            downloads = dirSize(sl.downloads.dir)
        )
    }

    fun clearPosters() {
        ServiceLocator.images.diskCache?.clear()
        ServiceLocator.images.memoryCache?.clear()
    }

    fun clearMetadata() {
        ServiceLocator.tmdb.dir.deleteRecursively()
        ServiceLocator.tmdb.dir.mkdirs()
    }

    /** Your watchlist, favourites and history are kept. */
    suspend fun clearIndex() = ServiceLocator.db.movies().clearIndexKeepUserData()

    suspend fun clearTemp() = ServiceLocator.tg.optimizeTemp(0, 0, all = true)

    /** Run when the app starts: enforce size limits and 3-day inactivity cleanup. */
    suspend fun autoClean(ctx: Context) {
        val p = ServiceLocator.prefs.value
        if (!p.autoClean) return
        val threeDays = 3L * 86400_000
        val inactive = p.lastOpen > 0 && System.currentTimeMillis() - p.lastOpen > threeDays
        if (inactive) { clearPosters() }
        ServiceLocator.tmdb.dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > threeDays) it.delete() }
        ServiceLocator.tg.optimizeTemp(p.tempCacheMb * 1024L * 1024L, (threeDays / 1000).toInt(), all = false)
    }

    /** Variant for the background worker (TDLib is not running there). */
    fun autoCleanOffline(ctx: Context) {
        val p = ServiceLocator.prefs.value
        if (!p.autoClean) return
        val threeDays = 3L * 86400_000
        if (p.lastOpen > 0 && System.currentTimeMillis() - p.lastOpen > threeDays) clearPosters()
        ServiceLocator.tmdb.dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > threeDays) it.delete() }
    }
}

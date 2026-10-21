package app.nwmovie.data

import android.content.Context
import app.nwmovie.ServiceLocator
import java.io.File

/**
 * Four separate categories, plus the Downloads folder which is never touched by cache clearing:
 *  1. Posters   – saved images (files/posters), kept until the user deletes them
 *  2. Metadata  – saved TMDB answers (files/tmdb), kept until the user deletes them
 *  3. Index     – the local movie list (Room database)
 *  4. Temporary – TDLib streaming/partial files (noBackup/td/files)
 */
object CacheManager {
    /** Below this much free space the phone counts as "low on storage". */
    const val LOW_STORAGE_BYTES = 5L * 1024 * 1024 * 1024

    fun freeBytes(ctx: Context): Long = try { android.os.StatFs(ctx.filesDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
    fun isLowStorage(ctx: Context): Boolean = freeBytes(ctx) < LOW_STORAGE_BYTES

    data class Usage(val posters: Long, val metadata: Long, val index: Long, val temp: Long, val downloads: Long) {
        val cacheTotal get() = posters + metadata + index + temp
    }

    fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun usage(ctx: Context): Usage {
        val sl = ServiceLocator
        val dbFile = ctx.getDatabasePath("nw.db")
        val index = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
        return Usage(
            posters = dirSize(File(ctx.filesDir, "posters")),
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
    suspend fun clearIndex() { ServiceLocator.db.movies().clearIndexKeepUserData(); ScanStore.clear() }

    suspend fun clearTemp() = ServiceLocator.tg.optimizeTemp(0, 0, all = true)

    /** Run when the app starts: enforce size limits and 3-day inactivity cleanup. */
    suspend fun autoClean(ctx: Context) {
        val p = ServiceLocator.prefs.value
        if (!p.autoClean) return
        val threeDays = p.cacheDays.coerceAtLeast(1) * 86400_000L
        // posters and TMDB details are never cleaned automatically: refetching them is what hits TMDB's limits
        ServiceLocator.tg.optimizeTemp(p.tempCacheMb * 1024L * 1024L, (threeDays / 1000).toInt(), all = false)
    }
}

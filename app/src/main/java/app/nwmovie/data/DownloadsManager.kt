package app.nwmovie.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.nwmovie.ServiceLocator
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.db.Movie
import app.nwmovie.data.telegram.Tg
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline downloads. Finished videos are MOVED to files/downloads (separate from caches),
 * so clearing any cache never deletes a downloaded movie.
 */
class DownloadsManager(private val ctx: Context, private val scope: CoroutineScope) {
    private val dao get() = ServiceLocator.db.downloads()
    private val tg get() = ServiceLocator.tg

    val dir = File(ctx.filesDir, "downloads").apply { mkdirs() }
    val items: Flow<List<DownloadItem>> by lazy { dao.all() }
    /** Live byte counts (the database is only updated a few times a second). */
    val progress = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val byFile = ConcurrentHashMap<Int, String>()
    private val lastWrite = ConcurrentHashMap<String, Long>()
    private val autoPaused = mutableSetOf<String>()

    /** (activeCount, overallPercent) for the foreground notification. */
    val summary: Flow<Pair<Int, Int>> by lazy {
        combine(items, progress) { list, prog ->
            val active = list.filter { it.state == DownloadItem.DOWNLOADING }
            val total = active.sumOf { it.total }.coerceAtLeast(1)
            val done = active.sumOf { prog[it.id] ?: it.done }
            active.size to (done * 100 / total).toInt()
        }
    }

    fun init() {
        scope.launch { tg.fileUpdates.collect { onFile(it) } }
        scope.launch {
            // After the app restarts, continue downloads that were running.
            tg.auth.filterIsInstance<Tg.Auth.Ready>().first()
            dao.list().filter { it.state == DownloadItem.DOWNLOADING }.forEach { begin(it) }
        }
        // Wi-Fi only: pause when leaving Wi-Fi.
        scope.launch {
            combine(NetPolicy.metered, NetPolicy.cellular, NetPolicy.online) { m, c, o -> Triple(m, c, o) }.collect {
                val p = ServiceLocator.prefs.value
                if (NetPolicy.downloadBlock(p) != null) {
                    dao.list().filter { it.state == DownloadItem.DOWNLOADING }.forEach { pause(it.id) }
                }
            }
        }
        // Background network control: optionally pause while the app is not on screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (!ServiceLocator.prefs.value.bgDownloads) scope.launch {
                    dao.list().filter { it.state == DownloadItem.DOWNLOADING }.forEach { autoPaused.add(it.id); pause(it.id) }
                }
            }
            override fun onStart(owner: LifecycleOwner) {
                if (autoPaused.isNotEmpty()) scope.launch {
                    autoPaused.toList().forEach { resume(it) }; autoPaused.clear()
                }
            }
        })
    }

    fun hasActive(): Boolean = byFile.isNotEmpty()

    suspend fun localPath(id: String): String? {
        val d = dao.get(id) ?: return null
        return if (d.state == DownloadItem.DONE && d.path.isNotEmpty() && File(d.path).exists()) d.path else null
    }

    /** Returns an error/blocked message, or null when the download started. */
    suspend fun enqueue(m: Movie): String? {
        NetPolicy.downloadBlock(ServiceLocator.prefs.value)?.let { return it }
        val existing = dao.get(m.id)
        if (existing != null && existing.state == DownloadItem.DONE) return "Already downloaded."
        val file = tg.refreshFile(m) ?: return "This file is no longer available in Telegram."
        val item = DownloadItem(m.id, file.id, m.displayTitle, m.poster, maxOf(file.size, file.expectedSize), 0,
            DownloadItem.DOWNLOADING, "", System.currentTimeMillis())
        dao.upsert(item)
        begin(item)
        return null
    }

    private suspend fun begin(item: DownloadItem) {
        byFile[item.fileId] = item.id
        tg.pin(item.fileId)
        dao.setState(item.id, DownloadItem.DOWNLOADING)
        try { tg.startDownload(item.fileId, 0, 0, 16) } catch (e: Exception) { dao.setState(item.id, DownloadItem.ERROR); return }
        startService()
    }

    private fun startService() {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) } catch (_: Exception) { }
    }

    suspend fun pause(id: String) {
        val d = dao.get(id) ?: return
        tg.cancelDownload(d.fileId)
        byFile.remove(d.fileId)
        dao.setState(id, DownloadItem.PAUSED)
    }

    suspend fun resume(id: String) {
        NetPolicy.downloadBlock(ServiceLocator.prefs.value)?.let { return }
        dao.get(id)?.let { begin(it) }
    }

    suspend fun cancel(id: String) {
        val d = dao.get(id) ?: return
        tg.cancelDownload(d.fileId); tg.unpin(d.fileId); byFile.remove(d.fileId)
        tg.deleteFile(d.fileId)
        dao.delete(id)
        progress.update { it - id }
    }

    suspend fun delete(id: String) {
        val d = dao.get(id)
        if (d != null && d.path.isNotEmpty()) File(d.path).delete()
        dao.delete(id)
        progress.update { it - id }
    }

    suspend fun deleteAll() {
        dao.list().forEach { cancel(it.id); delete(it.id) }
        dir.deleteRecursively(); dir.mkdirs()
    }

    private suspend fun onFile(f: TdApi_File) {
        val id = byFile[f.id] ?: return
        val done = if (f.local.isDownloadingCompleted) f.size else f.local.downloadedSize
        progress.update { it + (id to done) }
        val now = System.currentTimeMillis()
        if (now - (lastWrite[id] ?: 0) > 1000) {
            lastWrite[id] = now
            dao.setProgress(id, done, maxOf(f.size, f.expectedSize))
        }
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) finish(id, f)
    }

    private suspend fun finish(id: String, f: TdApi_File) {
        byFile.remove(f.id)
        val src = File(f.local.path)
        val d = dao.get(id) ?: return
        val safe = d.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "movie" }
        val ext = src.extension.ifEmpty { "mkv" }
        val dest = File(dir, "${id.replace('-', 'n')}_$safe.$ext")
        withContext(Dispatchers.IO) {
            if (!src.renameTo(dest)) { src.copyTo(dest, overwrite = true); src.delete() }
        }
        dao.upsert(d.copy(state = DownloadItem.DONE, path = dest.absolutePath, done = f.size, total = f.size))
        tg.unpin(f.id)
        tg.deleteFile(f.id) // drop TDLib's reference; the movie now lives in the Downloads folder
        progress.update { it - id }
    }
}

private typealias TdApi_File = org.drinkless.tdlib.TdApi.File

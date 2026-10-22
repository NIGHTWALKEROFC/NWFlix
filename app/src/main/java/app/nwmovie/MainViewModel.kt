package app.nwmovie

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nwmovie.data.CacheManager
import app.nwmovie.data.K
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.Updater
import app.nwmovie.data.qualityOf
import app.nwmovie.data.SortOption
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.db.Movie
import app.nwmovie.data.groupKey
import app.nwmovie.data.telegram.Tg
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.data.toGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sl = ServiceLocator

    val prefs = sl.prefs
    val auth = sl.tg.auth
    val authError = sl.tg.error
    val scan = sl.indexer.state
    val userName = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val searchOpen = MutableStateFlow(false)
    val sort = MutableStateFlow(SortOption.POPULAR)

    val movies: StateFlow<List<Movie>> = sl.db.movies().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Files of the same movie merged into one entry. */
    val groups: StateFlow<List<MovieGroup>> = movies.debounce(150).map { it.toGroups() }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Groups for Home: files that could not be matched are left out unless you already used them. */
    val homeGroups: StateFlow<List<MovieGroup>> = combine(groups, sl.prefs) { g, p ->
        if (!p.hideUnmatched) g else g.filter { val v = it.view; v.matched || v.lastPlayedAt > 0 || v.watchlist || v.favorite }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Set by Home to open Explore on a given filter (for example "Unmatched"). */
    val exploreGenre = MutableStateFlow<String?>(null)
    val downloads: StateFlow<List<Pair<DownloadItem, Long>>> =
        combine(sl.downloads.items, sl.downloads.progress) { list, prog -> list.map { it to (prog[it.id] ?: it.done) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var scanJob: Job? = null
    private var lastScan = 0L

    init {
        viewModelScope.launch {
            auth.collect { a ->
                if (a is Tg.Auth.Ready) {
                    userName.value = sl.tg.me()
                    // One deep scan after an update that changes what is recognised (TV episodes, captions, parts). Normal opens are incremental.
                    val deep = prefs.value.scanVersion < 5
                    rescan(deep)
                    if (deep) sl.settings.set(K.scanVersion, 5)
                    if (!prefs.value.dirBackfill) {                // one time: add directors to older saved details
                        sl.settings.set(K.dirBackfill, true)
                        try { sl.indexer.backfillDirectors() } catch (_: Exception) { }
                    }
                }
            }
        }
    }

    fun checkUpdates(manual: Boolean) { viewModelScope.launch { Updater.check(getApplication(), manual) } }
    fun continueUpdate() { viewModelScope.launch { Updater.continueIfPermitted(getApplication()) } }

    fun say(msg: String) { toast.tryEmit(msg) }

    // ----- settings -----
    fun <T> set(key: Preferences.Key<T>, value: T) { viewModelScope.launch { sl.settings.set(key, value) } }

    // ----- login -----
    private fun login(block: suspend () -> Unit) { viewModelScope.launch { busy.value = true; try { block() } finally { busy.value = false } } }
    fun clearAuthError() { sl.tg.error.value = null }
    fun submitPhone(p: String) = login { sl.tg.submitPhone(p) }
    fun submitCode(c: String) = login { sl.tg.submitCode(c) }
    fun submitPassword(p: String) = login { sl.tg.submitPassword(p) }
    fun resendCode() = login { sl.tg.resendCode() }
    fun logout() { viewModelScope.launch { sl.tg.logoutAndWipe(); sl.wipeLocalData() } }
    fun clearLocalData() { viewModelScope.launch { sl.wipeLocalData(); say("Local data cleared"); rescan(true) } }

    // ----- scanning (runs when the app opens, never in the background) -----
    fun rescan(full: Boolean) {
        lastScan = System.currentTimeMillis()
        scanJob = viewModelScope.launch { sl.indexer.scan(full) }
    }
    /** Called every time the app comes to the screen. */
    fun scanIfStale() {
        if (auth.value is Tg.Auth.Ready && System.currentTimeMillis() - lastScan > 30_000) rescan(false)
    }
    /** Called when the app leaves the screen. */

    // ----- library -----
    private fun idsOf(id: String): List<String> {
        val all = movies.value
        val m = all.firstOrNull { it.id == id } ?: return listOf(id)
        val k = m.groupKey()
        return all.filter { it.groupKey() == k }.map { it.id }
    }
    fun toggleWatch(m: Movie) { val ids = idsOf(m.id); val v = !m.watchlist; viewModelScope.launch { sl.db.movies().setWatchlistMany(ids, v) } }
    fun toggleFav(m: Movie) { val ids = idsOf(m.id); val v = !m.favorite; viewModelScope.launch { sl.db.movies().setFavoriteMany(ids, v) } }
    fun deleteMany(ids: Collection<String>) { viewModelScope.launch { ids.forEach { sl.downloads.delete(it) }; say("Deleted ${ids.size} download(s)") } }

    fun addSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        viewModelScope.launch {
            val rest = prefs.value.searchHistory.split("\n").filter { it.isNotBlank() && !it.equals(t, true) }
            sl.settings.set(K.searchHistory, (listOf(t) + rest).take(8).joinToString("\n"))
        }
    }
    fun clearSearchHistory() { viewModelScope.launch { sl.settings.set(K.searchHistory, "") } }

    fun clearAllHistory() { viewModelScope.launch { sl.db.movies().clearAllHistory(); say("Viewing history cleared") } }
    fun loadDetails(m: Movie) { viewModelScope.launch { try { sl.indexer.refreshDetails(m) } catch (_: Exception) { } } }

    suspend fun searchTmdb(q: String, year: Int, tv: Boolean = false): List<TmdbClient.Hit> = try { sl.tmdb.search(q, year, tv) } catch (e: Exception) { emptyList() }
    fun applyMatch(m: Movie, tmdbId: Int) {
        val ids = idsOf(m.id)
        viewModelScope.launch {
            try {
                ids.forEach { fid -> sl.db.movies().get(fid)?.let { sl.indexer.applyMatch(it, tmdbId) } }
                say("Details updated")
            } catch (e: Exception) { say("Couldn't load details. Please try again.") }
        }
    }

    fun setWatched(ids: List<String>, watched: Boolean) {
        viewModelScope.launch {
            if (watched) sl.db.movies().markWatched(ids, System.currentTimeMillis()) else sl.db.movies().markUnwatched(ids)
            say(if (watched) "Marked as watched" else "Marked as not watched")
        }
    }

    fun addWatch(id: String, ms: Long) { viewModelScope.launch { sl.db.movies().addWatched(id, ms) } }

    /** Instant play: fetch the first few MB while the detail page is open (Wi-Fi only, never with Data saver). */
    fun preload(f: Movie) {
        viewModelScope.launch {
            if (prefs.value.bandwidthSaver || NetPolicy.metered.value || NetPolicy.streamBlock(prefs.value) != null) return@launch
            if (CacheManager.isLowStorage(getApplication())) return@launch
            if (sl.downloads.localPath(f.id) != null) return@launch
            try { val tf = sl.tg.refreshFile(f); sl.tg.startDownload(tf?.id ?: f.fileId, 0, 6L * 1024 * 1024, 8) } catch (_: Exception) { }
        }
    }

    fun saveProgress(id: String, pos: Long, dur: Long) {
        if (dur <= 0 || pos <= 0) return
        viewModelScope.launch { sl.db.movies().progress(id, pos, dur, System.currentTimeMillis(), pos > dur * 0.92) }
    }

    /** Auto-play next: the next episode of a series (same quality), or the next unwatched movie in My List. */
    fun nextAfter(id: String): String? {
        val all = movies.value
        val cur = all.firstOrNull { it.id == id } ?: return null
        if (cur.isSeries) {
            val g = MovieGroup(all.filter { it.groupKey() == cur.groupKey() })
            val eps = g.episodesFor(qualityOf(cur))
            val i = eps.indexOfFirst { it.id == cur.id }
            if (i < 0) return null
            return eps.drop(i + 1).firstOrNull { it.season != cur.season || it.episode != cur.episode }?.id
        }
        val list = groups.value.filter { it.view.watchlist }.sortedBy { it.view.addedAt }
        val i = list.indexOfFirst { g -> g.files.any { it.id == id } }
        if (i < 0) return null
        return list.drop(i + 1).firstOrNull { !it.view.watched }?.playFile?.id
    }

    // ----- downloads (per file) -----
    fun download(m: Movie) { viewModelScope.launch { say(sl.downloads.enqueue(m) ?: "Download started") } }
}

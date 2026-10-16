package app.nwmovie

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nwmovie.data.K
import app.nwmovie.data.MovieGroup
import app.nwmovie.data.SortOption
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.db.Movie
import app.nwmovie.data.groupKey
import app.nwmovie.data.telegram.Tg
import app.nwmovie.data.tmdb.TmdbClient
import app.nwmovie.data.toGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val groups: StateFlow<List<MovieGroup>> = movies.map { it.toGroups() }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
                    val first = !prefs.value.capBackfill      // one deep scan after this update to read file captions
                    rescan(first)
                    if (first) sl.settings.set(K.capBackfill, true)
                }
            }
        }
    }

    fun say(msg: String) { toast.tryEmit(msg) }

    // ----- settings -----
    fun <T> set(key: Preferences.Key<T>, value: T) { viewModelScope.launch { sl.settings.set(key, value) } }

    // ----- login -----
    private fun login(block: suspend () -> Unit) { viewModelScope.launch { busy.value = true; try { block() } finally { busy.value = false } } }
    fun submitPhone(p: String) = login { sl.tg.submitPhone(p) }
    fun submitCode(c: String) = login { sl.tg.submitCode(c) }
    fun submitPassword(p: String) = login { sl.tg.submitPassword(p) }
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
    fun stopScan() { scanJob?.cancel() }

    // ----- library -----
    private fun idsOf(id: String): List<String> {
        val all = movies.value
        val m = all.firstOrNull { it.id == id } ?: return listOf(id)
        val k = m.groupKey()
        return all.filter { it.groupKey() == k }.map { it.id }
    }
    fun toggleWatch(m: Movie) { val ids = idsOf(m.id); val v = !m.watchlist; viewModelScope.launch { sl.db.movies().setWatchlistMany(ids, v) } }
    fun toggleFav(m: Movie) { val ids = idsOf(m.id); val v = !m.favorite; viewModelScope.launch { sl.db.movies().setFavoriteMany(ids, v) } }
    fun clearHistory(id: String) { val ids = idsOf(id); viewModelScope.launch { sl.db.movies().clearHistoryMany(ids) } }
    fun clearAllHistory() { viewModelScope.launch { sl.db.movies().clearAllHistory(); say("Viewing history cleared") } }
    fun loadDetails(m: Movie) { viewModelScope.launch { try { sl.indexer.refreshDetails(m) } catch (_: Exception) { } } }

    suspend fun searchTmdb(q: String, year: Int): List<TmdbClient.Hit> = try { sl.tmdb.search(q, year) } catch (e: Exception) { emptyList() }
    fun applyMatch(m: Movie, tmdbId: Int) {
        val ids = idsOf(m.id)
        viewModelScope.launch {
            try {
                ids.forEach { fid -> sl.db.movies().get(fid)?.let { sl.indexer.applyMatch(it, tmdbId) } }
                say("Details updated")
            } catch (e: Exception) { say("Couldn't load details. Please try again.") }
        }
    }

    fun saveProgress(id: String, pos: Long, dur: Long) {
        if (dur <= 0 || pos <= 0) return
        viewModelScope.launch { sl.db.movies().progress(id, pos, dur, System.currentTimeMillis(), pos > dur * 0.92) }
    }

    /** "Auto-play next": the next unwatched movie in My List after the one that just finished. */
    fun nextAfter(id: String): String? {
        val list = groups.value.filter { it.view.watchlist }.sortedBy { it.view.addedAt }
        val i = list.indexOfFirst { g -> g.files.any { it.id == id } }
        if (i < 0) return null
        return list.drop(i + 1).firstOrNull { !it.view.watched }?.playFile?.id
    }

    // ----- downloads (per file) -----
    fun download(m: Movie) { viewModelScope.launch { say(sl.downloads.enqueue(m) ?: "Download started") } }
    fun pauseDl(id: String) { viewModelScope.launch { sl.downloads.pause(id) } }
    fun resumeDl(id: String) { viewModelScope.launch { sl.downloads.resume(id) } }
    fun cancelDl(id: String) { viewModelScope.launch { sl.downloads.cancel(id) } }
    fun deleteDl(id: String) { viewModelScope.launch { sl.downloads.delete(id) } }
}

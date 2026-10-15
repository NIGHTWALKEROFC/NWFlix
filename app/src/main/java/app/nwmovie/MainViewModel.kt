package app.nwmovie

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nwmovie.data.db.DownloadItem
import app.nwmovie.data.db.Movie
import app.nwmovie.data.Secrets
import app.nwmovie.data.telegram.Tg
import app.nwmovie.data.tmdb.TmdbClient
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

    val movies: StateFlow<List<Movie>> = sl.db.movies().all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val downloads: StateFlow<List<Pair<DownloadItem, Long>>> =
        combine(sl.downloads.items, sl.downloads.progress) { list, prog -> list.map { it to (prog[it.id] ?: it.done) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            auth.collect { a ->
                if (a is Tg.Auth.Ready) {
                    userName.value = sl.tg.me()
                    rescan(false)
                }
            }
        }
    }

    fun say(msg: String) { toast.tryEmit(msg) }

    // ----- settings -----
    fun <T> set(key: Preferences.Key<T>, value: T) { viewModelScope.launch { sl.settings.set(key, value) } }

    // ----- login -----
    private fun login(block: suspend () -> Unit) { viewModelScope.launch { busy.value = true; try { block() } finally { busy.value = false } } }
    fun saveKeys(id: String, hash: String, tmdb: String) {
        val n = id.trim().toIntOrNull()
        if (n != null && hash.isNotBlank()) { Secrets.tgApiId = n; Secrets.tgApiHash = hash }
        if (tmdb.isNotBlank()) Secrets.tmdbKey = tmdb
        sl.tg.error.value = null
        sl.tg.start()
    }
    fun saveTmdbKey(k: String) { Secrets.tmdbKey = k; say("TMDB key saved"); rescan(true) }
    fun submitPhone(p: String) = login { sl.tg.submitPhone(p) }
    fun submitCode(c: String) = login { sl.tg.submitCode(c) }
    fun submitPassword(p: String) = login { sl.tg.submitPassword(p) }
    fun logout() { viewModelScope.launch { sl.tg.logoutAndWipe(); sl.wipeLocalData() } }
    fun clearLocalData() { viewModelScope.launch { sl.wipeLocalData(); say("Local data cleared"); rescan(true) } }

    // ----- library -----
    fun rescan(full: Boolean) { viewModelScope.launch { sl.indexer.scan(full) } }
    fun movie(id: String): Flow<Movie?> = sl.db.movies().observe(id)
    fun toggleWatch(m: Movie) { viewModelScope.launch { sl.db.movies().setWatchlist(m.id, !m.watchlist) } }
    fun toggleFav(m: Movie) { viewModelScope.launch { sl.db.movies().setFavorite(m.id, !m.favorite) } }
    fun clearHistory(id: String) { viewModelScope.launch { sl.db.movies().clearHistory(id) } }
    fun clearAllHistory() { viewModelScope.launch { sl.db.movies().clearAllHistory(); say("Viewing history cleared") } }
    fun loadDetails(m: Movie) { viewModelScope.launch { try { sl.indexer.refreshDetails(m) } catch (_: Exception) { } } }

    suspend fun searchTmdb(q: String, year: Int): List<TmdbClient.Hit> = try { sl.tmdb.search(q, year) } catch (e: Exception) { emptyList() }
    fun applyMatch(m: Movie, tmdbId: Int) {
        viewModelScope.launch {
            try { sl.indexer.applyMatch(m, tmdbId); say("Details updated") } catch (e: Exception) { say("Couldn't load details: ${e.message}") }
        }
    }

    fun saveProgress(id: String, pos: Long, dur: Long) {
        if (dur <= 0 || pos <= 0) return
        viewModelScope.launch { sl.db.movies().progress(id, pos, dur, System.currentTimeMillis(), pos > dur * 0.92) }
    }

    /** "Auto-play next": the next unwatched title in My List after the one that just finished. */
    fun nextAfter(id: String): String? {
        val list = movies.value.filter { it.watchlist }.sortedBy { it.addedAt }
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        return list.drop(i + 1).firstOrNull { !it.watched }?.id
    }

    // ----- downloads -----
    fun download(m: Movie) { viewModelScope.launch { say(sl.downloads.enqueue(m) ?: "Download started") } }
    fun pauseDl(id: String) { viewModelScope.launch { sl.downloads.pause(id) } }
    fun resumeDl(id: String) { viewModelScope.launch { sl.downloads.resume(id) } }
    fun cancelDl(id: String) { viewModelScope.launch { sl.downloads.cancel(id) } }
    fun deleteDl(id: String) { viewModelScope.launch { sl.downloads.delete(id) } }
}

package app.nwmovie.data

import app.nwmovie.data.db.AppDb
import app.nwmovie.data.db.Movie
import app.nwmovie.data.telegram.FilenameParser
import app.nwmovie.data.telegram.RawItem
import app.nwmovie.data.telegram.Tg
import app.nwmovie.data.tmdb.TmdbAuthException
import app.nwmovie.data.tmdb.TmdbClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Scans Telegram → filters non-movies → matches with TMDB → stores the local movie index. */
class Indexer(private val tg: Tg, private val tmdb: TmdbClient, private val db: AppDb) {
    data class State(val running: Boolean = false, val phase: String = "", val found: Int = 0, val matched: Int = 0, val error: String? = null)

    val state = MutableStateFlow(State())
    private val running = AtomicBoolean(false)

    suspend fun scan(full: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try {
            state.value = State(true, "Scanning your Telegram chats…")
            val known = db.movies().keys().toSet()
            val raws = tg.scan(known, full) { n -> state.value = state.value.copy(found = n) }
            val fresh = raws.filter { FilenameParser.looksLikeMovie(it) }.mapNotNull { toMovie(it) }
            db.movies().insertAll(fresh)

            if (!tmdb.hasKey()) {
                state.value = State(false, error = "Add a free TMDB API key in Settings → Account & Security to get posters and details.", found = fresh.size)
                return
            }
            val cutoff = if (full) Long.MAX_VALUE else System.currentTimeMillis() - 3L * 86400_000
            val todo = db.movies().unmatched(cutoff)
            state.value = State(true, "Fetching movie details…", found = fresh.size)
            val sem = Semaphore(4)
            val done = AtomicInteger(0)
            var authFailed = false
            coroutineScope {
                todo.map { m ->
                    async {
                        sem.withPermit {
                            try { matchOne(m) } catch (e: TmdbAuthException) { authFailed = true } catch (_: Exception) { }
                            val d = done.incrementAndGet()
                            state.value = state.value.copy(matched = d, phase = "Fetching movie details… $d/${todo.size}")
                        }
                    }
                }.awaitAll()
            }
            state.value = State(false, found = fresh.size, error = if (authFailed) "TMDB rejected your API key. Check it in Settings → Account & Security." else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.value = State(false, error = e.message ?: "Scan failed")
        } finally {
            running.set(false)
        }
    }

    private fun toMovie(r: RawItem): Movie? {
        val p = FilenameParser.parse(r.name, r.caption) ?: return null
        return Movie(
            id = "${r.chatId}_${r.messageId}", chatId = r.chatId, messageId = r.messageId, fileId = r.fileId,
            fileName = r.name, size = r.size, durationSec = r.durationSec, mime = r.mime,
            parsedTitle = p.title, parsedYear = p.year, addedAt = r.dateSec * 1000L
        )
    }

    private suspend fun matchOne(m: Movie) {
        val hit = TmdbClient.pickBest(m.parsedTitle, m.parsedYear, tmdb.search(m.parsedTitle, m.parsedYear))
        if (hit == null) { db.movies().update(m.copy(triedAt = System.currentTimeMillis())); return }
        applyDetail(m, tmdb.details(hit.id))
    }

    private suspend fun applyDetail(m: Movie, d: TmdbClient.Detail) {
        db.movies().update(
            m.copy(
                tmdbId = d.id, title = d.title, year = d.year, rating = d.rating, popularity = d.popularity, genres = d.genres,
                overview = d.overview, poster = d.poster, backdrop = d.backdrop, runtime = d.runtime, cast = d.cast,
                triedAt = System.currentTimeMillis()
            )
        )
    }

    /** Used when the user fixes a wrong match by hand. */
    suspend fun applyMatch(m: Movie, tmdbId: Int) = applyDetail(db.movies().get(m.id) ?: m, tmdb.details(tmdbId))

    /** Loads cast etc. for a movie that has no details yet. */
    suspend fun refreshDetails(m: Movie) { if (m.tmdbId > 0) applyDetail(db.movies().get(m.id) ?: m, tmdb.details(m.tmdbId)) }
}

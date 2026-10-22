package app.nwmovie.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "movies")
data class Movie(
    @PrimaryKey val id: String,              // "<chatId>_<messageId>"
    val chatId: Long, val messageId: Long, val fileId: Int,
    val fileName: String, val size: Long, val durationSec: Int, val mime: String,
    val parsedTitle: String, val parsedYear: Int,
    val tmdbId: Int = 0, val title: String = "", val year: Int = 0,
    val rating: Double = 0.0, val popularity: Double = 0.0, val genres: String = "",
    val overview: String = "", val poster: String = "", val backdrop: String = "",
    val runtime: Int = 0, val cast: String = "[]",
    val addedAt: Long = 0, val triedAt: Long = 0,
    val lastPlayedAt: Long = 0, val positionMs: Long = 0, val durationMs: Long = 0,
    val watchlist: Boolean = false, val favorite: Boolean = false, val watched: Boolean = false,
    @ColumnInfo(defaultValue = "''") val caption: String = "",
    @ColumnInfo(defaultValue = "0") val season: Int = 0,
    @ColumnInfo(defaultValue = "0") val episode: Int = 0,
    @ColumnInfo(defaultValue = "0") val isSeries: Boolean = false,
    @ColumnInfo(defaultValue = "0") val part: Int = 0,
    @ColumnInfo(defaultValue = "0") val watchedMs: Long = 0
) {
    val matched: Boolean get() = tmdbId > 0
    val displayTitle: String get() = if (matched && title.isNotBlank()) title else parsedTitle.ifBlank { fileName }
    val displayYear: Int get() = if (matched) year else parsedYear
    val genreList: List<String> get() = genres.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey val id: String,      // same as Movie.id
    val fileId: Int, val title: String, val poster: String,
    val total: Long, val done: Long, val state: String, val path: String = "", val createdAt: Long = 0
) {
    companion object {
        const val QUEUED = "QUEUED"; const val DOWNLOADING = "DOWNLOADING"; const val PAUSED = "PAUSED"
        const val DONE = "DONE"; const val ERROR = "ERROR"
    }
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies ORDER BY addedAt DESC") fun all(): Flow<List<Movie>>
    @Query("SELECT * FROM movies WHERE id = :id") suspend fun get(id: String): Movie?
    @Query("SELECT id FROM movies") suspend fun keys(): List<String>
    @Query("SELECT * FROM movies WHERE tmdbId = 0 AND triedAt < :before LIMIT :n") suspend fun unmatchedBefore(before: Long, n: Int): List<Movie>
    @Query("SELECT COUNT(*) FROM movies WHERE tmdbId = 0 AND triedAt < :before") suspend fun countUnmatchedBefore(before: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(list: List<Movie>)
    @Update suspend fun update(m: Movie)
    @Query("UPDATE movies SET fileId = :fileId WHERE id = :id") suspend fun setFileId(id: String, fileId: Int)
    @Query("UPDATE movies SET positionMs = :pos, durationMs = :dur, lastPlayedAt = :t, watched = :w WHERE id = :id")
    suspend fun progress(id: String, pos: Long, dur: Long, t: Long, w: Boolean)
    @Query("UPDATE movies SET lastPlayedAt = 0, positionMs = 0") suspend fun clearAllHistory()
    @Query("UPDATE movies SET lastPlayedAt = 0, positionMs = 0 WHERE id IN (:ids)") suspend fun clearHistoryFor(ids: List<String>)
    @Query("SELECT * FROM movies") suspend fun allOnce(): List<Movie>
    @Query("UPDATE movies SET watchedMs = watchedMs + :ms WHERE id = :id") suspend fun addWatched(id: String, ms: Long)
    @Query("UPDATE movies SET watched = 1, positionMs = 0, lastPlayedAt = CASE WHEN lastPlayedAt = 0 THEN :t ELSE lastPlayedAt END WHERE id IN (:ids)")
    suspend fun markWatched(ids: List<String>, t: Long)
    @Query("UPDATE movies SET watched = 0, positionMs = 0, lastPlayedAt = 0 WHERE id IN (:ids)") suspend fun markUnwatched(ids: List<String>)
    @Query("UPDATE movies SET cast = :cast WHERE id = :id") suspend fun setCast(id: String, cast: String)
    @Query("UPDATE movies SET caption = :c WHERE id = :id AND caption = ''") suspend fun setCaption(id: String, c: String)
    @Query("UPDATE movies SET watchlist = :v WHERE id IN (:ids)") suspend fun setWatchlistMany(ids: List<String>, v: Boolean)
    @Query("UPDATE movies SET favorite = :v WHERE id IN (:ids)") suspend fun setFavoriteMany(ids: List<String>, v: Boolean)
    /** Clears the index but keeps anything the user personally saved (lists, favourites, history). */
    @Query("DELETE FROM movies WHERE watchlist = 0 AND favorite = 0 AND lastPlayedAt = 0") suspend fun clearIndexKeepUserData()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun all(): Flow<List<DownloadItem>>
    @Query("SELECT * FROM downloads") suspend fun list(): List<DownloadItem>
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun get(id: String): DownloadItem?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadItem)
    @Query("UPDATE downloads SET state = :state WHERE id = :id") suspend fun setState(id: String, state: String)
    @Query("DELETE FROM downloads WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE downloads SET done = :done, total = :total WHERE id = :id") suspend fun setProgress(id: String, done: Long, total: Long)
}

@Database(entities = [Movie::class, DownloadItem::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun movies(): MovieDao
    abstract fun downloads(): DownloadDao
}

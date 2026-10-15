package app.nwmovie.data.tmdb

import android.content.Context
import app.nwmovie.data.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class TmdbAuthException : IOException("TMDB rejected the API key")

/**
 * Minimal TMDB v3 client (free for non-commercial use).
 * Responses are cached on disk in cache/tmdb = the "Metadata cache".
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
class TmdbClient(ctx: Context) {
    data class Hit(val id: Int, val title: String, val year: Int, val poster: String, val popularity: Double, val overview: String)
    data class Detail(
        val id: Int, val title: String, val year: Int, val rating: Double, val popularity: Double, val genres: String,
        val overview: String, val poster: String, val backdrop: String, val runtime: Int, val cast: String
    )

    val dir = File(ctx.cacheDir, "tmdb").apply { mkdirs() }
    fun hasKey() = Secrets.tmdbKey.isNotBlank()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private suspend fun get(path: String, q: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val f = File(dir.apply { mkdirs() }, sha(path + q.toSortedMap().toString()))
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 14L * 86400_000) {
            try { return@withContext JSONObject(f.readText()) } catch (_: Exception) { f.delete() }
        }
        val query = (q + mapOf("api_key" to Secrets.tmdbKey, "language" to "en-US"))
            .entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        var attempt = 0
        while (true) {
            val conn = (URL("https://api.themoviedb.org/3$path?$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                if (code == 401) throw TmdbAuthException()
                if (code == 429 && attempt++ < 3) { Thread.sleep(1500L * attempt); continue }
                if (code !in 200..299) throw IOException("TMDB error $code")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                f.writeText(text)
                return@withContext JSONObject(text)
            } finally { conn.disconnect() }
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }

    private fun year(date: String?): Int = date?.take(4)?.toIntOrNull() ?: 0

    suspend fun search(title: String, year: Int): List<Hit> {
        suspend fun run(y: Int): List<Hit> {
            val params = mutableMapOf("query" to title, "include_adult" to "false")
            if (y > 0) params["year"] = y.toString()
            val arr = get("/search/movie", params).optJSONArray("results") ?: JSONArray()
            return (0 until minOf(arr.length(), 10)).map { i ->
                val o = arr.getJSONObject(i)
                Hit(o.getInt("id"), o.optString("title"), year(o.optString("release_date")),
                    o.optString("poster_path").takeIf { it != "null" } ?: "", o.optDouble("popularity", 0.0), o.optString("overview"))
            }
        }
        val first = run(year)
        return if (first.isEmpty() && year > 0) run(0) else first
    }

    suspend fun details(id: Int): Detail {
        val o = get("/movie/$id", mapOf("append_to_response" to "credits"))
        val genres = o.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(",") { g.getJSONObject(it).optString("name") } } ?: ""
        val castArr = o.optJSONObject("credits")?.optJSONArray("cast") ?: JSONArray()
        val cast = JSONArray()
        for (i in 0 until minOf(castArr.length(), 14)) {
            val c = castArr.getJSONObject(i)
            cast.put(JSONObject().put("n", c.optString("name")).put("c", c.optString("character"))
                .put("p", c.optString("profile_path").takeIf { it != "null" } ?: ""))
        }
        return Detail(
            id, o.optString("title"), year(o.optString("release_date")), o.optDouble("vote_average", 0.0),
            o.optDouble("popularity", 0.0), genres, o.optString("overview"),
            o.optString("poster_path").takeIf { it != "null" } ?: "", o.optString("backdrop_path").takeIf { it != "null" } ?: "",
            o.optInt("runtime", 0), cast.toString()
        )
    }

    companion object {
        fun img(path: String, size: String = "w342"): String? = if (path.isBlank()) null else "https://image.tmdb.org/t/p/$size$path"
        fun pickBest(title: String, year: Int, hits: List<Hit>): Hit? {
            fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            val nt = norm(title)
            if (nt.isBlank()) return null
            return hits.map { h ->
                var s = 0
                val nh = norm(h.title)
                if (nh == nt) s += 4 else if (nh.contains(nt) || nt.contains(nh)) s += 1
                if (year > 0 && h.year > 0) s += if (h.year == year) 3 else if (kotlin.math.abs(h.year - year) == 1) 1 else -3
                else if (year == 0) s += 1
                h to s
            }.filter { it.second >= 3 }.maxWithOrNull(compareBy({ it.second }, { it.first.popularity }))?.first
        }
    }
}

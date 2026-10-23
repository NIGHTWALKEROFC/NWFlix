package app.nwmovie.data

import android.content.Context
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Remembers what the user has already opened, so new movies and new episodes can be marked.
 * The first time the library is ready, everything that exists is counted as already seen.
 */
object NewStore {
    private val sp by lazy { ServiceLocator.app.getSharedPreferences("nw_seen", Context.MODE_PRIVATE) }
    val changed = MutableStateFlow(0)

    private fun key(g: MovieGroup) = "g" + g.view.groupKey()

    fun baselined(): Boolean = sp.getLong("baselineAt", 0L) > 0L

    fun baseline(groups: List<MovieGroup>) {
        val e = sp.edit()
        groups.forEach { e.putInt(key(it), if (it.isSeries) it.episodeCount else 1) }
        e.putLong("baselineAt", System.currentTimeMillis()).apply()
        changed.value++
    }

    fun markSeen(g: MovieGroup) {
        val v = if (g.isSeries) g.episodeCount else 1
        if (sp.getInt(key(g), -1) != v) {
            sp.edit().putInt(key(g), v).apply()
            changed.value++
        }
    }

    /** "NEW" for a movie added since you last looked, "NEW EP" / "3 NEW EPS" for a series with unopened episodes. */
    @Suppress("UNUSED_PARAMETER")
    fun newLabel(g: MovieGroup, rev: Int = 0): String? {
        val base = sp.getLong("baselineAt", 0L)
        if (base == 0L) return null
        val seen = sp.getInt(key(g), -1)
        if (g.isSeries) {
            if (seen >= 0) {
                val n = g.episodeCount - seen
                return if (n == 1) "NEW EP" else if (n > 1) "$n NEW EPS" else null
            }
            return if (g.view.addedAt > base) "NEW" else null
        }
        return if (seen < 0 && g.view.lastPlayedAt == 0L && g.view.addedAt > base) "NEW" else null
    }

    fun clear() {
        sp.edit().clear().apply()
        changed.value++
    }
}

/** Watch time per movie / series for the last 7 days, used for the "Top 10 this week" row. */
object WeeklyStats {
    private val sp by lazy { ServiceLocator.app.getSharedPreferences("nw_week", Context.MODE_PRIVATE) }
    private val fmt get() = SimpleDateFormat("yyyyMMdd", Locale.US)

    private fun day(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        return fmt.format(c.time)
    }

    fun add(groupKey: String, ms: Long) {
        if (ms <= 0) return
        val k = "${day(0)}|$groupKey"
        sp.edit().putLong(k, sp.getLong(k, 0L) + ms).apply()
    }

    /** Watch time in the last 7 days (today included), by group key. Older days are removed. */
    fun lastWeek(): Map<String, Long> {
        val days = (0..6).map { day(-it) }.toSet()
        val oldest = day(-8)
        val out = HashMap<String, Long>()
        val stale = ArrayList<String>()
        for ((k, v) in sp.all) {
            val d = k.substringBefore('|')
            if (d in days) { val g = k.substringAfter('|'); out[g] = (out[g] ?: 0L) + ((v as? Long) ?: 0L) }
            else if (d < oldest) stale.add(k)
        }
        if (stale.isNotEmpty()) sp.edit().apply { stale.forEach { remove(it) } }.apply()
        return out
    }

    fun clear() { sp.edit().clear().apply() }
}

/** The titles for "Top 10 this week": most watched in the last 7 days first, then the most watched overall. */
fun topTen(groups: List<MovieGroup>): Pair<List<MovieGroup>, Boolean> {
    val week = WeeklyStats.lastWeek()
    val byWeek = groups.filter { (week[it.view.groupKey()] ?: 0L) > 0 }
        .sortedByDescending { week[it.view.groupKey()] ?: 0L }
    val rest = groups.filter { g -> byWeek.none { it.id == g.id } && g.files.sumOf { it.watchedMs } > 0 }
        .sortedByDescending { g -> g.files.sumOf { it.watchedMs } }
    return (byWeek + rest).take(10) to (byWeek.size >= 3)
}

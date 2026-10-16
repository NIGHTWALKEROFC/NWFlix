package app.nwmovie.data

import android.content.Context
import app.nwmovie.ServiceLocator

/**
 * Remembers, for every chat, the last message that was already checked.
 * The next scan only looks at messages newer than that, so movies that were loaded once are never scanned again.
 * "Clear movie index" / "Full rescan" / logout reset it, which makes the next scan start from the beginning.
 */
object ScanStore {
    private val sp by lazy { ServiceLocator.app.getSharedPreferences("nw_scan", Context.MODE_PRIVATE) }
    fun get(chatId: Long): Long = sp.getLong("c$chatId", 0L)
    fun set(chatId: Long, lastMessageId: Long) { sp.edit().putLong("c$chatId", lastMessageId).apply() }
    fun clear() { sp.edit().clear().apply() }
}

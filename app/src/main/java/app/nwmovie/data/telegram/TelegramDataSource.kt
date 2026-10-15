package app.nwmovie.data.telegram

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Streams a Telegram file straight into ExoPlayer (uri = tg://file/<fileId>) without a manual download.
 * It asks TDLib to download from the byte position the player needs (so seeking is quick),
 * then reads the bytes as they arrive. While bytes are still on the way, ExoPlayer shows "buffering".
 */
@UnstableApi
class TelegramDataSource(
    private val tg: Tg,
    private val windowBytes: () -> Long
) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var fileId = 0
    private var pos = 0L
    private var remaining = 0L
    private var total = 0L
    private var windowEnd = Long.MAX_VALUE
    private var lastKick = 0L
    private var opened = false
    private var buf = ByteArray(0)
    private var bufPos = 0
    private var bufLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        fileId = dataSpec.uri.lastPathSegment?.toIntOrNull() ?: throw IOException("Bad Telegram uri")
        val f = try { runBlocking { tg.getFile(fileId) } } catch (e: Exception) {
            throw IOException("Telegram file unavailable: ${e.message}")
        }
        total = if (f.size > 0) f.size else f.expectedSize
        pos = dataSpec.position
        if (total > 0 && pos >= total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - pos
        kick(pos)
        bufPos = 0; bufLen = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun kick(from: Long) {
        val w = windowBytes()
        windowEnd = if (w <= 0) Long.MAX_VALUE else from + w
        lastKick = SystemClock.elapsedRealtime()
        try { runBlocking { tg.startDownload(fileId, from, w) } } catch (_: Exception) { }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        if (bufPos >= bufLen) fill()
        val n = minOf(length.toLong(), (bufLen - bufPos).toLong(), remaining).toInt()
        System.arraycopy(buf, bufPos, target, offset, n)
        bufPos += n; pos += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun fill() {
        val want = minOf(CHUNK.toLong(), remaining, if (total > 0) total - pos else Long.MAX_VALUE).toInt()
        val started = SystemClock.elapsedRealtime()
        while (true) {
            // Slide the download window forward before the player reaches its end.
            if (windowEnd != Long.MAX_VALUE && pos + want > windowEnd - EARLY) kick(pos)
            val part = runBlocking { tg.readPart(fileId, pos, want) }
            if (part != null) { buf = part; bufPos = 0; bufLen = part.size; return }
            val now = SystemClock.elapsedRealtime()
            if (now - started > 60_000) throw IOException("Telegram did not deliver data in time. Check your connection.")
            if (now - lastKick > 5_000) kick(pos) // nudge TDLib if the download went idle
            Thread.sleep(40)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
            if (!tg.isPinned(fileId)) try { runBlocking { tg.cancelDownload(fileId) } } catch (_: Exception) { }
        }
    }

    class Factory(private val tg: Tg, private val windowBytes: () -> Long) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(tg, windowBytes)
    }

    private companion object {
        const val CHUNK = 256 * 1024
        const val EARLY = 4L * 1024 * 1024
    }
}

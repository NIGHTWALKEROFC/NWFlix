package app.nwmovie.player

import android.os.SystemClock
import app.nwmovie.ServiceLocator
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny web server that only listens on this phone (127.0.0.1) and only answers requests that carry a random secret.
 * It lets the VLC engine stream a Telegram file (supports seeking via HTTP Range), using the same TDLib reading as the normal player.
 */
object LocalStreamServer {
    private var server: ServerSocket? = null
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun urlFor(fileId: Int): String {
        if (server?.isClosed != false) {
            val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server = s
            thread(isDaemon = true, name = "nw-stream-accept") {
                while (!s.isClosed) {
                    try { val c = s.accept(); thread(isDaemon = true, name = "nw-stream") { handle(c) } } catch (_: IOException) { break }
                }
            }
        }
        return "http://127.0.0.1:${server!!.localPort}/$token/$fileId"
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val lines = ArrayList<String>()
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return
                if (b == '\n'.code) { val l = sb.toString().trimEnd('\r'); sb.setLength(0); if (l.isEmpty()) break else lines.add(l) } else sb.append(b.toChar())
                if (lines.size > 64 || sb.length > 8192) return
            }
            val req = lines.firstOrNull()?.split(" ") ?: return
            val path = req.getOrNull(1)?.trim('/')?.split("/") ?: return
            val out = sock.getOutputStream()
            if (path.size != 2 || path[0] != token) { out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            val fileId = path[1].toIntOrNull() ?: return
            val tg = ServiceLocator.tg
            val f = runBlocking { tg.getFile(fileId) }
            val total = if (f.size > 0) f.size else f.expectedSize
            val range = lines.firstOrNull { it.startsWith("range:", true) }?.substringAfter(':')?.trim()
            var start = 0L
            var end = total - 1
            if (range != null && range.startsWith("bytes=")) {
                val r = range.removePrefix("bytes=").split("-")
                start = r.getOrNull(0)?.toLongOrNull() ?: 0L
                r.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, total - 1) }
            }
            if (start >= total) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
            val partial = range != null
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                append("Content-Length: ${end - start + 1}\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                append("\r\n")
            }
            out.write(head.toByteArray())
            if (req[0].equals("HEAD", true)) return

            var pos = start
            val window = if (ServiceLocator.prefs.value.bandwidthSaver) 16L * 1024 * 1024 else 128L * 1024 * 1024
            var windowEnd = pos + window
            var lastKick = SystemClock.elapsedRealtime()
            runBlocking { try { tg.startDownload(fileId, pos, window) } catch (_: Exception) { } }
            try {
                while (pos <= end) {
                    val want = minOf(256L * 1024, end - pos + 1).toInt()
                    if (pos + want > windowEnd - 4L * 1024 * 1024 && windowEnd < total) {
                        windowEnd = pos + window; lastKick = SystemClock.elapsedRealtime()
                        runBlocking { try { tg.startDownload(fileId, pos, window) } catch (_: Exception) { } }
                    }
                    val begun = SystemClock.elapsedRealtime()
                    var part: ByteArray? = null
                    while (part == null) {
                        part = runBlocking { tg.readPart(fileId, pos, want) }
                        if (part == null) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - begun > 60_000) return                       // Telegram did not deliver: end the response, the player will retry
                            if (now - lastKick > 5_000) { lastKick = now; runBlocking { try { tg.startDownload(fileId, pos, window) } catch (_: Exception) { } } }
                            Thread.sleep(40)
                        }
                    }
                    out.write(part)
                    pos += part.size
                }
                out.flush()
            } finally {
                if (!tg.isPinned(fileId)) runBlocking { try { tg.cancelDownload(fileId) } catch (_: Exception) { } }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }
}

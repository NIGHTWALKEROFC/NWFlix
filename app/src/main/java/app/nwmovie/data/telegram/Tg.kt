package app.nwmovie.data.telegram

import android.app.Application
import android.os.Build
import app.nwmovie.BuildConfig
import app.nwmovie.data.Secrets
import app.nwmovie.data.db.Movie
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TgException(val code: Int, message: String) : Exception(message)

/** A movie-sized video/document message found in Telegram. */
data class RawItem(
    val chatId: Long, val messageId: Long, val fileId: Int, val name: String, val size: Long,
    val durationSec: Int, val mime: String, val caption: String, val dateSec: Int
)

/**
 * The ONLY class that talks to TDLib. If a TDLib update ever renames something,
 * this is the one file to fix.
 */
class Tg(private val app: Application, private val scope: CoroutineScope) {

    sealed interface Auth {
        data object Loading : Auth
        data object NeedKeys : Auth
        data object WaitPhone : Auth
        data object WaitCode : Auth
        data object WaitPassword : Auth
        data object Ready : Auth
        data class Unsupported(val msg: String) : Auth
    }

    val auth = MutableStateFlow<Auth>(Auth.Loading)
    val error = MutableStateFlow<String?>(null)
    val fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile var wasReady = false
        private set

    @Volatile private var client: Client? = null
    private var wipeOnClose = false
    private val pinned: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    val baseDir: File get() = File(app.noBackupFilesDir, "td")
    /** TDLib's cache of streamed/partially downloaded files = "Temporary playback data". */
    val tempDir: File get() = File(baseDir, "files")

    // ---------- lifecycle ----------

    @Synchronized
    fun start() {
        if (client != null) return
        if (!Secrets.hasTelegramKeys()) { auth.value = Auth.NeedKeys; return }
        auth.value = Auth.Loading
        Client.execute(TdApi.SetLogVerbosityLevel(0)) // never write TDLib logs (they can contain private data)
        client = Client.create({ obj -> onUpdate(obj) }, null, null)
    }

    private fun onUpdate(o: TdApi.Object) {
        when (o) {
            is TdApi.UpdateAuthorizationState -> onAuth(o.authorizationState)
            is TdApi.UpdateFile -> fileUpdates.tryEmit(o.file)
            else -> Unit
        }
    }

    private fun onAuth(s: TdApi.AuthorizationState) {
        when (s) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                baseDir.mkdirs()
                val p = TdApi.SetTdlibParameters()
                p.databaseDirectory = File(baseDir, "db").absolutePath
                p.filesDirectory = tempDir.absolutePath
                p.databaseEncryptionKey = Secrets.dbKey()
                p.useFileDatabase = true
                p.useChatInfoDatabase = true
                p.useMessageDatabase = true
                p.useSecretChats = false
                p.apiId = Secrets.tgApiId
                p.apiHash = Secrets.tgApiHash
                p.systemLanguageCode = "en"
                p.deviceModel = Build.MODEL
                p.systemVersion = Build.VERSION.RELEASE
                p.applicationVersion = BuildConfig.VERSION_NAME
                client?.send(p) { r -> if (r is TdApi.Error) error.value = friendly(r.message) }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> auth.value = Auth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> auth.value = Auth.WaitCode
            is TdApi.AuthorizationStateWaitPassword -> auth.value = Auth.WaitPassword
            is TdApi.AuthorizationStateReady -> { wasReady = true; auth.value = Auth.Ready }
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> auth.value = Auth.Loading
            is TdApi.AuthorizationStateClosed -> onClosed()
            else -> auth.value = Auth.Unsupported(
                "Telegram asked for a step this app can't handle yet. Finish it in the official Telegram app, then try again."
            )
        }
    }

    private fun onClosed() {
        client = null
        if (wipeOnClose) {
            wipeOnClose = false
            baseDir.deleteRecursively()
            Secrets.resetDbKey()
        }
        auth.value = Auth.Loading
        start()
    }

    // ---------- generic request helper ----------

    suspend fun <T : TdApi.Object> call(f: TdApi.Function<T>): T = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            cont.resumeWithException(TgException(-1, "Telegram is not connected"))
        } else {
            c.send(f) { r ->
                if (r is TdApi.Error) cont.resumeWithException(TgException(r.code, r.message))
                else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(r as T)
                }
            }
        }
    }

    // ---------- login ----------

    private suspend fun <T : TdApi.Object> authCall(f: TdApi.Function<T>) {
        error.value = null
        try { call(f) } catch (e: TgException) { error.value = friendly(e.message ?: "") }
    }

    suspend fun submitPhone(phone: String) = authCall(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    suspend fun submitCode(code: String) = authCall(TdApi.CheckAuthenticationCode(code.trim()))
    suspend fun submitPassword(pw: String) = authCall(TdApi.CheckAuthenticationPassword(pw))

    suspend fun logoutAndWipe() {
        wipeOnClose = true
        try { call(TdApi.LogOut()) } catch (e: Exception) { client?.send(TdApi.Close()) { } }
    }

    suspend fun me(): String = try {
        val u = call(TdApi.GetMe())
        "${u.firstName} ${u.lastName}".trim().ifBlank { "Telegram user" }
    } catch (e: Exception) { "Telegram user" }

    private fun friendly(m: String): String = when {
        m.contains("PHONE_NUMBER_INVALID") -> "That phone number isn't valid. Use international format, e.g. +91 98765 43210."
        m.contains("PHONE_CODE_INVALID") || m.contains("PHONE_CODE_EMPTY") -> "That code is wrong. Check the latest code in your Telegram app."
        m.contains("PHONE_CODE_EXPIRED") -> "That code expired. Go back and request a new one."
        m.contains("PASSWORD_HASH_INVALID") -> "Wrong two-step verification password."
        m.contains("FLOOD") || m.contains("Too Many Requests") -> "Too many attempts. Wait a few minutes and try again."
        m.contains("API_ID") -> "Telegram rejected the api_id / api_hash. Re-check them at my.telegram.org."
        else -> m.ifBlank { "Something went wrong talking to Telegram." }
    }

    // ---------- scanning for movies ----------

    suspend fun scan(known: Set<String>, full: Boolean, onFound: (Int) -> Unit): List<RawItem> {
        // Make sure the chat list is loaded (TDLib answers 404 once everything is loaded).
        repeat(6) {
            try { call(TdApi.LoadChats(TdApi.ChatListMain(), 200)) } catch (e: TgException) { return@repeat }
        }
        val ids = call(TdApi.GetChats(TdApi.ChatListMain(), 500)).chatIds
        val out = Collections.synchronizedList(mutableListOf<RawItem>())
        val sem = Semaphore(3)
        coroutineScope {
            ids.map { cid ->
                async {
                    sem.withPermit {
                        try { scanChat(cid, known, full, out) } catch (_: Exception) { }
                        onFound(out.size)
                    }
                }
            }.awaitAll()
        }
        return out.toList()
    }

    private suspend fun scanChat(chatId: Long, known: Set<String>, full: Boolean, out: MutableList<RawItem>) {
        val filters = listOf<TdApi.SearchMessagesFilter>(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterDocument())
        for (flt in filters) {
            var from = 0L
            var pages = 0
            var retries = 0
            val maxPages = if (full) 40 else 6
            while (pages < maxPages) {
                val req = TdApi.SearchChatMessages()
                req.chatId = chatId
                req.query = ""
                req.fromMessageId = from
                req.offset = 0
                req.limit = 100
                req.filter = flt
                val res = try { call(req) } catch (e: TgException) {
                    if (e.code == 429 && retries++ < 3) {
                        val wait = Regex("\\d+").find(e.message ?: "")?.value?.toLongOrNull() ?: 3L
                        delay((wait + 1) * 1000); continue
                    }
                    break
                }
                val msgs = res.messages
                if (msgs == null || msgs.isEmpty()) break
                var fresh = 0
                for (m in msgs) {
                    val raw = toRaw(m) ?: continue
                    if ("${raw.chatId}_${raw.messageId}" !in known) { out.add(raw); fresh++ }
                }
                if (!full && fresh == 0) break
                from = res.nextFromMessageId
                if (from == 0L) break
                pages++
            }
        }
    }

    private fun toRaw(m: TdApi.Message): RawItem? = when (val c = m.content) {
        is TdApi.MessageVideo -> {
            val v = c.video
            RawItem(m.chatId, m.id, v.video.id, v.fileName ?: "", v.video.size, v.duration, v.mimeType ?: "video/mp4", c.caption?.text ?: "", m.date)
        }
        is TdApi.MessageDocument -> {
            val d = c.document
            RawItem(m.chatId, m.id, d.document.id, d.fileName ?: "", d.document.size, 0, d.mimeType ?: "", c.caption?.text ?: "", m.date)
        }
        else -> null
    }

    // ---------- files / streaming ----------

    suspend fun refreshFile(m: Movie): TdApi.File? = try {
        when (val c = call(TdApi.GetMessage(m.chatId, m.messageId)).content) {
            is TdApi.MessageVideo -> c.video.video
            is TdApi.MessageDocument -> c.document.document
            else -> null
        }
    } catch (e: Exception) { null }

    suspend fun getFile(id: Int): TdApi.File = call(TdApi.GetFile(id))

    suspend fun startDownload(fileId: Int, offset: Long, limit: Long = 0, priority: Int = 32) {
        val f = TdApi.DownloadFile()
        f.fileId = fileId
        f.priority = priority
        f.offset = offset
        f.limit = limit
        f.synchronous = false
        call(f)
    }

    /** Returns null while the requested bytes have not arrived yet (the player shows "buffering"). */
    suspend fun readPart(fileId: Int, offset: Long, count: Int): ByteArray? = try {
        call(TdApi.ReadFilePart(fileId, offset, count.toLong())).data?.takeIf { it.isNotEmpty() }
    } catch (e: TgException) { null }

    suspend fun cancelDownload(fileId: Int) { try { call(TdApi.CancelDownloadFile(fileId, false)) } catch (_: Exception) { } }
    suspend fun deleteFile(fileId: Int) { try { call(TdApi.DeleteFile(fileId)) } catch (_: Exception) { } }

    fun pin(id: Int) { pinned.add(id) }
    fun unpin(id: Int) { pinned.remove(id) }
    fun isPinned(id: Int) = pinned.contains(id)

    /** Cleans TDLib's cache. all=true wipes everything cached; otherwise applies size + age limits. */
    suspend fun optimizeTemp(sizeBytes: Long, ttlSeconds: Int, all: Boolean) {
        val f = TdApi.OptimizeStorage()
        f.size = if (all) 0L else sizeBytes
        f.ttl = if (all) 0 else ttlSeconds
        f.count = if (all) 0 else -1
        f.immunityDelay = if (all) 0 else -1
        f.fileTypes = arrayOf<TdApi.FileType>()
        f.chatIds = LongArray(0)
        f.excludeChatIds = LongArray(0)
        f.returnDeletedFileStatistics = false
        f.chatLimit = 0
        try { call(f) } catch (_: Exception) { }
    }
}

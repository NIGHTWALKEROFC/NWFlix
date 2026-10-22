package app.nwmovie.ui.screens

import app.nwmovie.ui.theme.NWButton
import app.nwmovie.ui.theme.NWOutlinedButton
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.provider.Settings
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import app.nwmovie.AppState
import app.nwmovie.MainActivity
import app.nwmovie.MainViewModel
import app.nwmovie.ServiceLocator
import app.nwmovie.data.CacheManager
import app.nwmovie.data.K
import app.nwmovie.data.fileTags
import app.nwmovie.data.groupKey
import app.nwmovie.data.partKey
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.db.Movie
import app.nwmovie.player.PlayerManager
import app.nwmovie.player.PlayerRouting
import app.nwmovie.player.Sub
import app.nwmovie.player.SubtitleParser
import app.nwmovie.util.findActivity
import app.nwmovie.util.fmtSize
import app.nwmovie.util.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class TrackOpt(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean, val supported: Boolean)

private fun trackOpts(t: Tracks, type: Int): List<TrackOpt> {
    var n = 0
    val out = mutableListOf<TrackOpt>()
    for (g in t.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            n++
            val f = g.getTrackFormat(i)
            val base = trackName(f.label, f.language, n)
            val extra = when (type) {
                C.TRACK_TYPE_AUDIO -> listOfNotNull(f.sampleMimeType?.substringAfter('/')?.uppercase(), if (f.channelCount > 0) "${f.channelCount}ch" else null)
                C.TRACK_TYPE_VIDEO -> listOfNotNull(if (f.height > 0) "${f.height}p" else null, if (f.bitrate > 0) "${f.bitrate / 1000} kbps" else null)
                else -> emptyList()
            }
            out += TrackOpt(g, i, base + if (extra.isNotEmpty()) "  •  " + extra.joinToString(" ") else "", g.isTrackSelected(i), g.isTrackSupported(i))
        }
    }
    return out
}

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
        "This phone can't decode this video or audio format. Try turning hardware acceleration off (Settings → Player), or download it and open it in another player."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "This file's format isn't supported or the file is damaged."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
        "Couldn't read the movie from Telegram. Check your connection and try again."
    else -> "Playback error (${e.errorCodeName})."
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onNext: (String) -> Unit, onVlc: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val view = LocalView.current
    val prefs by vm.prefs.collectAsState()
    val pip by AppState.inPip.collectAsState()
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current

    val player = remember(id) { PlayerManager.create() }
    var movie by remember { mutableStateOf<Movie?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingText by remember { mutableStateOf<String?>("Connecting to Telegram…") }
    var attempt by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var vsize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var show by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var pendingSeek by remember { mutableLongStateOf(-1L) }

    var parts by remember { mutableStateOf<List<Movie>>(emptyList()) }     // several files = parts of one movie, played in a row
    var curIdx by remember { mutableIntStateOf(0) }
    val playedFileIds = remember { mutableListOf<Int>() }
    var locked by remember { mutableStateOf(false) }                        // touch lock
    fun cid(): String = parts.getOrNull(curIdx)?.id ?: id
    var manualRotate by remember { mutableStateOf(false) }
    var aspect by remember { mutableIntStateOf(0) }          // 0 fit, 1 stretch, 2 zoom
    val lowAtStart = remember { CacheManager.isLowStorage(ctx) }
    var sleepAt by remember { mutableLongStateOf(0L) }          // 0 off, -1 end of movie, else epoch ms
    var ext by remember { mutableStateOf<List<Sub>>(emptyList()) }
    var extName by remember { mutableStateOf("") }
    var extOn by remember { mutableStateOf(false) }
    var subDelay by remember { mutableIntStateOf(prefs.subDelayMs) }
    var audioDelay by remember { mutableIntStateOf(prefs.audioDelayMs) }

    // ---------- start playback ----------
    LaunchedEffect(id, attempt) {
        error = null; loadingText = "Connecting to Telegram…"
        val p = ServiceLocator.prefs.value
        val m = ServiceLocator.db.movies().get(id)
        if (m == null) { error = "Movie not found."; loadingText = null; return@LaunchedEffect }
        movie = m
        // Formats ExoPlayer cannot open (AVI, WMV, FLV…) go straight to the VLC engine.
        if (AppState.forceStandard != id && (p.engine == "vlc" || (p.engine == "auto" && PlayerRouting.preferVlc(m.fileName)))) { onVlc(id); return@LaunchedEffect }
        // Low on space: free old streamed data first so this movie plays without piling up cache.
        if (lowAtStart && !ServiceLocator.downloads.hasActive()) try { ServiceLocator.tg.optimizeTemp(0, 0, all = true) } catch (_: Exception) { }
        // Parts of one multi-part movie (CD1/CD2, Part 1/Part 2) are played one after another.
        val pk = partKey(m)
        val list = if (pk != null) {
            val sib = ServiceLocator.db.movies().allOnce().filter { it.groupKey() == m.groupKey() && partKey(it) == pk }.sortedBy { it.part }
            if (sib.size >= 2 && sib.map { it.part }.distinct().size >= 2) sib else listOf(m)
        } else listOf(m)
        parts = list
        val items = mutableListOf<PlayerManager.PlayItem>()
        for (f in list) {
            val local = ServiceLocator.downloads.localPath(f.id)
            var fileId: Int? = null
            if (local == null) {
                val block = NetPolicy.streamBlock(p)
                if (block != null) { error = block; loadingText = null; return@LaunchedEffect }
                val found = resolveTgFile(f)
                if (found == null) {
                    error = "This file is no longer available in Telegram. It may have been deleted, or you lost access to the chat."
                    loadingText = null; return@LaunchedEffect
                }
                val (src, tf) = found
                fileId = tf.id
                playedFileIds.add(tf.id)
                if (src.id == f.id && tf.id != f.fileId) ServiceLocator.db.movies().setFileId(f.id, tf.id)
            }
            items += PlayerManager.PlayItem(f, local, fileId)
        }
        val startIdx = list.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val cur = list[startIdx]
        val start = if (AppState.switchTo == id) { AppState.switchTo = null; AppState.switchMs } else if (p.resume && cur.positionMs > 5000 && cur.durationMs > 0 && cur.positionMs < cur.durationMs * 0.95) cur.positionMs else 0L
        curIdx = startIdx
        PlayerManager.openAll(items, startIdx, start)
        loadingText = null
    }

    var upNext by remember { mutableStateOf<Movie?>(null) }                   // "Up next" countdown card
    var upSec by remember { mutableIntStateOf(10) }
    var versions by remember { mutableStateOf<List<Movie>>(emptyList()) }     // other files of the same movie
    var bufLong by remember { mutableStateOf(false) }

    // ---------- volume: 0-200 % like VLC (up to 100 % = phone volume, above that = extra gain) ----------
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volPct by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol * 100f) }
    val enhancer = remember { arrayOfNulls<LoudnessEnhancer>(1) }
    /** Volume boost above 100 % (Android's built-in loudness effect). */
    fun applyEffects() {
        val boost = ((volPct.coerceIn(0f, 200f) - 100f).coerceAtLeast(0f) * 10f).toInt()      // 200 % = +10 dB
        val gain = boost
        try { enhancer[0]?.let { e -> e.setTargetGain(gain); e.setEnabled(gain > 0) } } catch (_: Exception) { }
    }
    fun applyVolume(pct: Float) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (pct.coerceIn(0f, 200f).coerceAtMost(100f) / 100f * maxVol).roundToInt(), 0)
        applyEffects()
    }
    // keep the on-screen value in step with the phone's volume buttons
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val s = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol * 100f
            if ((volPct <= 100f && abs(s - volPct) > 100f / maxVol) || (volPct > 100f && s < 99f)) volPct = s
        }
    }

    // ---------- listener ----------
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; AppState.playerPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    val d = player.duration.takeIf { it > 0 } ?: 0L
                    vm.saveProgress(cid(), d, d)
                    if (sleepAt == -1L) { sleepAt = 0; vm.say("Sleep timer: stopped at the end"); return }
                    if (ServiceLocator.prefs.value.autoNext) vm.nextAfter(cid())?.let { nid -> scope.launch { upNext = ServiceLocator.db.movies().get(nid); upSec = 10 } }
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                enhancer[0]?.release()
                enhancer[0] = if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) null else try { LoudnessEnhancer(audioSessionId) } catch (e: Exception) { null }
                applyVolume(volPct)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val prev = curIdx
                curIdx = player.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) parts.getOrNull(prev)?.let { vm.saveProgress(it.id, 1, 1) }   // finished part counts as watched
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onVideoSizeChanged(v: VideoSize) { vsize = v }
            override fun onPlayerError(e: PlaybackException) {
                error = describe(e)
                // Format ExoPlayer cannot handle: continue in the VLC engine from the same position.
                if (ServiceLocator.prefs.value.engine == "auto" && AppState.forceStandard != cid() && PlayerRouting.isFormatError(e.errorCode)) {
                    AppState.switchTo = cid(); AppState.switchMs = player.currentPosition; onVlc(cid())
                }
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); enhancer[0]?.release(); enhancer[0] = null }
    }

    // ---------- clock ----------
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(200)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (dur > 0 && pos > 0) vm.saveProgress(cid(), pos, dur)
            if (isPlaying) vm.addWatch(cid(), 5000)
        }
    }

    LaunchedEffect(buffering) { if (buffering) { delay(8000); bufLong = true } else bufLong = false }
    LaunchedEffect(sheet) {
        if (sheet == "versions") {
            val cur = parts.getOrNull(curIdx) ?: movie
            versions = if (cur == null) emptyList() else ServiceLocator.db.movies().allOnce().filter {
                it.groupKey() == cur.groupKey() && it.id != cur.id && it.part == 0 && (!cur.isSeries || (it.season == cur.season && it.episode == cur.episode))
            }.sortedByDescending { it.size }
        }
    }
    // "Up next": 10 second countdown, then the next episode starts by itself
    LaunchedEffect(upNext) {
        if (upNext != null) {
            while (upSec > 0) { delay(1000); upSec-- }
            upNext?.let { n -> upNext = null; onNext(n.id) }
        }
    }

    // ---------- sleep timer ----------
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            player.pause(); sleepAt = 0; vm.say("Sleep timer: paused")
        }
    }

    // ---------- window: bars, orientation, brightness ----------
    var bright by remember { mutableFloatStateOf(-1f) }
    DisposableEffect(Unit) {
        AppState.playerOpen = true
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        if (prefs.fullscreen && ctrl != null) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE      // opens in landscape like MX / VLC
        val cur = window?.attributes?.screenBrightness ?: -1f
        val sys = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (e: Exception) { 0.5f }
        bright = (if (cur >= 0) cur else sys).coerceIn(0.1f, 1f)
        // Bring back the brightness / volume the user chose last time (values that look like "0" are ignored).
        if (prefs.rememberBrightness && prefs.savedBrightness >= 0.05f && window != null) {
            bright = prefs.savedBrightness.coerceIn(0.05f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = bright }
        }
        if (prefs.rememberVolume && prefs.savedVolume >= 5) {
            volPct = prefs.savedVolume.toFloat().coerceIn(5f, 200f)
            applyVolume(volPct)
        }
        onDispose {
            AppState.playerOpen = false; AppState.playerPlaying = false; AppState.forceStandard = null
            val d = player.duration
            if (d > 0) vm.saveProgress(cid(), player.currentPosition, d)
            PlayerManager.releaseIfCurrent(player)
            // Low on storage (under 5 GB) or "always": remove the streamed movie data as soon as the user leaves the player.
            val mode = ServiceLocator.prefs.value.clearAfterWatch
            if (mode == "always" || lowAtStart || (mode == "auto" && CacheManager.isLowStorage(ctx))) {
                val ids = playedFileIds.toList()
                ServiceLocator.scope.launch { delay(1500); ids.forEach { if (!ServiceLocator.tg.isPinned(it)) ServiceLocator.tg.deleteFile(it) } }
            }
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                window.attributes = window.attributes.apply { screenBrightness = WindowManagerBrightnessNone }
            }
        }
    }
    // the screen that was just closed (the other player) can reset the orientation a moment after this one opened
    LaunchedEffect(Unit) {
        delay(500)
        if (!manualRotate && vsize.height <= vsize.width) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    LaunchedEffect(vsize) {
        // A tall (phone-recorded) video opens upright; the rotate button still works either way.
        if (!manualRotate && vsize.width > 0 && vsize.height > vsize.width) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // background playback off → pause when the app leaves the screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !ServiceLocator.prefs.value.bgPlayback && !AppState.inPip.value) player.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // auto-hide controls
    LaunchedEffect(show, isPlaying, sheet, touchTick) {
        if (show && isPlaying && sheet == null) { delay(3500); show = false }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }


    fun seekBy(ms: Long) { player.seekTo((player.currentPosition + ms).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)) }
    fun selectTrack(o: TrackOpt) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.group.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index)).build()
    }
    fun disableText() { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val subs = withContext(Dispatchers.IO) {
                try { ctx.contentResolver.openInputStream(uri)?.use { SubtitleParser.parse(SubtitleParser.decode(it.readBytes())) } ?: emptyList() }
                catch (e: Exception) { emptyList() }
            }
            if (subs.isEmpty()) vm.say("Couldn't read that subtitle file (use .srt, .vtt or .ass)")
            else { ext = subs; extName = uri.lastPathSegment?.substringAfterLast('/') ?: "Subtitle file"; extOn = true; disableText(); vm.say("Subtitles loaded") }
        }
    }

    val audioOpts = remember(tracks) { trackOpts(tracks, C.TRACK_TYPE_AUDIO) }
    val textOpts = remember(tracks) { trackOpts(tracks, C.TRACK_TYPE_TEXT) }
    val noSupportedAudio = audioOpts.isNotEmpty() && audioOpts.none { it.selected }

    val cueText = remember(pos, ext, subDelay, extOn) {
        if (!extOn) null else { val t = pos - subDelay; ext.firstOrNull { t >= it.start && t < it.end }?.text }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- video ----------
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; keepScreenOn = true; setShutterBackgroundColor(android.graphics.Color.BLACK); this.player = player } },
                update = { v ->
                    v.resizeMode = when (aspect) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    v.subtitleView?.apply {
                        visibility = if (extOn) android.view.View.GONE else android.view.View.VISIBLE
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.subSize)
                        setStyle(CaptionStyleCompat(prefs.subColor, if (prefs.subBg) 0x99000000.toInt() else 0, 0,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, 0xFF000000.toInt(), null))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---------- external subtitles (with delay) ----------
        if (cueText != null && !pip) Text(
            cueText, color = Color(prefs.subColor), fontSize = prefs.subSize.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (show) 110.dp else 30.dp, start = 24.dp, end = 24.dp)
                .background(if (prefs.subBg) Color.Black.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
        )

        // ---------- gestures ----------
        Box(
            Modifier.fillMaxSize()
                .pointerInput(prefs.doubleTapSec, locked) {
                    if (locked) {
                        detectTapGestures(onTap = { show = !show; touchTick++ })       // locked: a tap only shows the unlock button
                    } else detectTapGestures(
                        onPress = {
                            // Long-press = 2x speed while the finger is down
                            var boost = false
                            val job = scope.launch { delay(450); boost = true; player.setPlaybackSpeed(2f); hud = "2× speed" }
                            tryAwaitRelease()
                            job.cancel()
                            if (boost) { player.setPlaybackSpeed(speed); hud = null }
                        },
                        onTap = { show = !show; touchTick++ },
                        onDoubleTap = { o ->
                            val s = prefs.doubleTapSec * 1000L
                            if (o.x < size.width / 2f) { seekBy(-s); hud = "−${prefs.doubleTapSec}s" } else { seekBy(s); hud = "+${prefs.doubleTapSec}s" }
                        }
                    )
                }
                .pointerInput(prefs.gestures, prefs.brightnessGesture, prefs.volumeGesture, prefs.seekGesture, locked) {
                    if (!prefs.gestures || locked) return@pointerInput
                    var mode = 0; var left = true; var acc = 0f; var startPos = 0L
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; left = o.x < size.width / 2f; acc = 0f; startPos = player.currentPosition; pendingSeek = -1 },
                        onDragEnd = {
                            if (mode == 1 && pendingSeek >= 0) player.seekTo(pendingSeek)
                            if (mode == 2) {
                                if (left && prefs.rememberBrightness) vm.set(K.savedBrightness, bright)
                                else if (!left && prefs.rememberVolume) vm.set(K.savedVolume, volPct.roundToInt())
                            }
                            pendingSeek = -1; mode = 0
                        },
                        onDragCancel = { pendingSeek = -1; mode = 0 },
                        onDrag = { change, d ->
                            change.consume()
                            if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 1 else 2
                            if (mode == 1 && prefs.seekGesture && dur > 0) {
                                acc += d.x / size.width * 120_000f
                                val t = (startPos + acc).toLong().coerceIn(0, dur)
                                pendingSeek = t
                                hud = "${fmtTime(t)}  (${if (acc >= 0) "+" else "−"}${fmtTime(abs(acc).toLong())})"
                            } else if (mode == 2) {
                                val delta = -d.y / size.height * 1.3f
                                if (left && prefs.brightnessGesture) {
                                    bright = (bright + delta).coerceIn(0.05f, 1f)
                                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
                                    hud = "Brightness ${(bright * 100).roundToInt()}%"
                                } else if (!left && prefs.volumeGesture) {
                                    volPct = (volPct + delta * 200f).coerceIn(0f, 200f)
                                    applyVolume(volPct)
                                    hud = "Volume ${volPct.roundToInt()}%" + if (volPct > 100f) "  (boost)" else ""
                                }
                            }
                        }
                    )
                }
        )

        // ---------- loading / buffering indicator ----------
        if (error == null && (loadingText != null || buffering) && !pip) Column(
            Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            Text(loadingText ?: "Loading…", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            if (bufLong && loadingText == null) TextButton(onClick = { sheet = "versions" }) { Text("Slow? Try another version", color = Color(0xFF9CB4FF)) }
        }

        hud?.let { h ->
            Text(h, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // ---------- error ----------
        error?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171825))) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B8A), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Can't play this movie", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = Color(0xFFBABCD2), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NWOutlinedButton(onClick = onBack) { Text("Back") }
                        NWButton(onClick = { attempt++ }) { Text("Try again") }
                    }
                    TextButton(onClick = { AppState.switchTo = cid(); AppState.switchMs = pos; onVlc(cid()) }) { Text("Play with the VLC engine") }
                }
            }
        }

        // ---------- controls ----------
        BackHandler(enabled = locked) { show = true }
        if (locked && show && !pip && error == null) IconButton(
            onClick = { locked = false; hud = "Controls unlocked" },
            modifier = Modifier.align(Alignment.CenterStart).padding(24.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
        ) { Icon(Icons.Default.Lock, "Unlock controls", tint = Color.White) }
        if (!pip && error == null) AnimatedVisibility(show && !locked, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))

                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text(if (prefs.hideNotif) "" else playerTitle(parts.getOrNull(curIdx) ?: movie, parts.size, curIdx), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = { locked = true; show = false; hud = "Controls locked. Tap the screen to show the unlock button." }) { Icon(Icons.Default.LockOpen, "Lock controls", tint = Color.White) }
                    IconButton(onClick = { (activity as? MainActivity)?.enterPip() }) { Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    IconButton(onClick = {
                        manualRotate = true
                        activity?.requestedOrientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }) { Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White) }
                    IconButton(onClick = { sheet = "more" }) { Icon(Icons.Default.Tune, "More", tint = Color.White) }
                }

                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { seekBy(-10_000); touchTick++ }, Modifier.size(52.dp)) { Icon(Icons.Default.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
                    FilledIconButton(onClick = { if (isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }; touchTick++ },
                        Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.18f))) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play / pause", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { seekBy(10_000); touchTick++ }, Modifier.size(52.dp)) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    if (noSupportedAudio) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("No supported audio track (e.g. DTS).", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { AppState.switchTo = cid(); AppState.switchMs = pos; onVlc(cid()) }) { Text("Use VLC engine") }
                    }
                    Slider(
                        value = scrub ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scrub = it; touchTick++ },
                        onValueChangeFinished = { scrub?.let { player.seekTo((it * dur).toLong()) }; scrub = null }
                    )
                    Row {
                        Text(fmtTime(if (scrub != null) (scrub!! * dur).toLong() else pos), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(fmtTime(dur), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PlayerBtn(Icons.Default.Subtitles, "Subtitles") { sheet = "subs" }
                        PlayerBtn(Icons.Default.Audiotrack, "Audio") { sheet = "audio" }
                        PlayerBtn(Icons.Default.Speed, "${speed}x") { sheet = "speed" }
                        PlayerBtn(Icons.Default.AspectRatio, listOf("Fit", "Stretch", "Zoom")[aspect]) {
                            aspect = (aspect + 1) % 3
                            hud = listOf("Fit to screen", "Stretch", "Zoom to fill")[aspect]
                        }
                        PlayerBtn(Icons.Default.MoreHoriz, "More") { sheet = "more" }
                    }
                }
            }
        }

        upNext?.let { nm ->
            Surface(Modifier.align(Alignment.BottomEnd).padding(20.dp).widthIn(max = 340.dp), shape = RoundedCornerShape(16.dp), color = Color(0xE6101830)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Up next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(playerTitle(nm, 1, 0), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("Starting in ${upSec}s", color = Color(0xFFBABCD2), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NWOutlinedButton(onClick = { upNext = null }) { Text("Cancel") }
                        NWButton(onClick = { val i = nm.id; upNext = null; onNext(i) }) { Text("Play now") }
                    }
                }
            }
        }

        // ---------- sheets ----------
        if (!pip) when (sheet) {
            "subs" -> PlayerSheet("Subtitles", { sheet = null }) {
                PlayerOpt("Off", !extOn && textOpts.none { it.selected }) { extOn = false; disableText() }
                textOpts.forEach { o -> PlayerOpt(o.label, !extOn && o.selected) { extOn = false; selectTrack(o) } }
                if (ext.isNotEmpty()) PlayerOpt("$extName (loaded file)", extOn) { extOn = true; disableText() }
                NWOutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Load subtitle file (.srt .vtt .ass)") }
                Text("Subtitle delay: $subDelay ms", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> NWOutlinedButton(onClick = { subDelay += d }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { subDelay = 0 }) { Text("Reset") }
                }
                Text("Delay applies to loaded subtitle files. Positive = subtitles appear later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "audio" -> PlayerSheet("Audio", { sheet = null }) {
                if (audioOpts.isEmpty()) Text("No selectable audio tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                audioOpts.forEach { o -> PlayerOpt(o.label + if (!o.supported) "  (not supported)" else "", o.selected, o.supported) { selectTrack(o) } }
                Text("Audio delay: $audioDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-100, 100, 500).forEach { d ->
                        NWOutlinedButton(onClick = { audioDelay = (audioDelay + d).coerceIn(0, 2000); PlayerManager.audioDelay.delayMs = audioDelay }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") }
                    }
                    TextButton(onClick = { audioDelay = 0; PlayerManager.audioDelay.delayMs = 0 }) { Text("Reset") }
                }
                Text("Use this when the voices are ahead of the picture (0–2000 ms).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "speed" -> PlayerSheet("Playback speed", { sheet = null }) {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s -> PlayerOpt("${s}x", speed == s) { speed = s; player.setPlaybackSpeed(s) } }
            }
            "versions" -> PlayerSheet("Switch version", { sheet = null }) {
                if (versions.isEmpty()) Text("No other version of this movie was found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                versions.forEach { v ->
                    val t = fileTags(v).joinToString("  •  ").ifEmpty { "Video file" }
                    PlayerOpt("$t  (${fmtSize(v.size)})", false) {
                        AppState.switchTo = v.id; AppState.switchMs = if (parts.size <= 1) player.currentPosition else 0L
                        sheet = null; onNext(v.id)          // opens the other file at the same minute
                    }
                }
                if (versions.isNotEmpty()) Text("Playback continues from the same time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "sleep" -> PlayerSheet("Sleep timer", { sheet = null }) {
                PlayerOpt("Off", sleepAt == 0L) { sleepAt = 0 }
                listOf(15, 30, 45, 60, 90).forEach { m -> PlayerOpt("$m minutes", false) { sleepAt = System.currentTimeMillis() + m * 60_000L; vm.say("Sleep timer: $m min"); sheet = null } }
                PlayerOpt("End of movie", sleepAt == -1L) { sleepAt = -1; sheet = null }
            }
            "more" -> PlayerSheet("More", { sheet = null }) {
                PlayerAction(Icons.Default.Bedtime, "Sleep timer${if (sleepAt != 0L) " (on)" else ""}") { sheet = "sleep" }
                PlayerAction(Icons.Default.VideoLibrary, "Switch version (other files of this movie)") { sheet = "versions" }
                PlayerAction(Icons.Default.Movie, "Play with the VLC engine (all formats)") { AppState.switchTo = cid(); AppState.switchMs = pos; sheet = null; onVlc(cid()) }
                val m = movie
                Text("Info", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text(buildString {
                    if (!prefs.hideNotif) append(m?.fileName?.ifBlank { null } ?: "Telegram video").append('\n')
                    if (vsize.width > 0) append("${vsize.width}×${vsize.height}  •  ")
                    if (m != null) append(fmtSize(m.size))
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val WindowManagerBrightnessNone = -1f

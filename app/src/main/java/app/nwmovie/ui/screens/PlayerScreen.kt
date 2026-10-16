package app.nwmovie.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.provider.Settings
import android.util.TypedValue
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
import app.nwmovie.data.K
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.db.Movie
import app.nwmovie.player.PlayerManager
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
            val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
            val base = f.label ?: lang ?: "Track $n"
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
fun PlayerScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onNext: (String) -> Unit) {
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
    var aspect by remember { mutableStateOf(prefs.aspect) }
    var show by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var pendingSeek by remember { mutableLongStateOf(-1L) }

    var abA by remember { mutableLongStateOf(-1L) }
    var abB by remember { mutableLongStateOf(-1L) }
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
        val local = ServiceLocator.downloads.localPath(id)
        var fileId: Int? = null
        if (local == null) {
            val block = NetPolicy.streamBlock(p)
            if (block != null) { error = block; loadingText = null; return@LaunchedEffect }
            val f = ServiceLocator.tg.refreshFile(m)
            if (f == null) {
                error = "This file is no longer available in Telegram. It may have been deleted, or you lost access to the chat."
                loadingText = null; return@LaunchedEffect
            }
            fileId = f.id
            if (f.id != m.fileId) ServiceLocator.db.movies().setFileId(m.id, f.id)
        }
        val start = if (p.resume && m.positionMs > 5000 && m.durationMs > 0 && m.positionMs < m.durationMs * 0.95) m.positionMs else 0L
        PlayerManager.open(m, start, local, fileId)
        loadingText = null
    }

    var askSaver by remember { mutableStateOf(false) }

    // ---------- volume: 0-200 % like VLC (up to 100 % = phone volume, above that = extra gain) ----------
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volPct by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol * 100f) }
    val enhancer = remember { arrayOfNulls<LoudnessEnhancer>(1) }
    fun applyVolume(pct: Float) {
        val v = pct.coerceIn(0f, 200f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceAtMost(100f) / 100f * maxVol).roundToInt(), 0)
        try {
            enhancer[0]?.let { e ->
                val gain = ((v - 100f).coerceAtLeast(0f) * 10f).toInt()      // 200 % = +10 dB
                e.setTargetGain(gain)
                e.setEnabled(gain > 0)
            }
        } catch (_: Exception) { }
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
                    vm.saveProgress(id, d, d)
                    if (sleepAt == -1L) { sleepAt = 0; vm.say("Sleep timer: stopped at the end"); return }
                    if (ServiceLocator.prefs.value.autoNext) vm.nextAfter(id)?.let { onNext(it) }
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                enhancer[0]?.release()
                enhancer[0] = if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) null else try { LoudnessEnhancer(audioSessionId) } catch (e: Exception) { null }
                applyVolume(volPct)
            }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onVideoSizeChanged(v: VideoSize) { vsize = v }
            override fun onPlayerError(e: PlaybackException) { error = describe(e) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); enhancer[0]?.release(); enhancer[0] = null }
    }

    // ---------- clock: position, A-B repeat ----------
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            if (abA >= 0 && abB > abA && pos >= abB) player.seekTo(abA)
            delay(200)
        }
    }
    LaunchedEffect(player) { while (true) { delay(5000); if (dur > 0 && pos > 0) vm.saveProgress(id, pos, dur) } }

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
        activity?.requestedOrientation = when (prefs.rotation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
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
            AppState.playerOpen = false; AppState.playerPlaying = false
            val d = player.duration
            if (d > 0) vm.saveProgress(id, player.currentPosition, d)
            PlayerManager.releaseIfCurrent(player)
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                window.attributes = window.attributes.apply { screenBrightness = WindowManagerBrightnessNone }
            }
        }
    }
    LaunchedEffect(vsize) {
        if (prefs.rotation == "video" && vsize.width > 0) activity?.requestedOrientation =
            if (vsize.width >= vsize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
    val videoOpts = remember(tracks) { trackOpts(tracks, C.TRACK_TYPE_VIDEO) }
    val noSupportedAudio = audioOpts.isNotEmpty() && audioOpts.none { it.selected }

    val cueText = remember(pos, ext, subDelay, extOn) {
        if (!extOn) null else { val t = pos - subDelay; ext.firstOrNull { t >= it.start && t < it.end }?.text }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- video ----------
        val ratioMod = when (aspect) { "16:9" -> Modifier.aspectRatio(16f / 9f); "4:3" -> Modifier.aspectRatio(4f / 3f); else -> Modifier.fillMaxSize() }
        Box(Modifier.align(Alignment.Center).then(ratioMod)) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; keepScreenOn = true; setShutterBackgroundColor(android.graphics.Color.BLACK); this.player = player } },
                update = { v ->
                    v.resizeMode = when (aspect) {
                        "fill", "16:9", "4:3" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        "crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
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
                .pointerInput(prefs.doubleTapSec) {
                    detectTapGestures(
                        onTap = { show = !show; touchTick++ },
                        onDoubleTap = { o ->
                            val s = prefs.doubleTapSec * 1000L
                            if (o.x < size.width / 2f) { seekBy(-s); hud = "−${prefs.doubleTapSec}s" } else { seekBy(s); hud = "+${prefs.doubleTapSec}s" }
                        }
                    )
                }
                .pointerInput(prefs.gestures, prefs.brightnessGesture, prefs.volumeGesture, prefs.seekGesture) {
                    if (!prefs.gestures) return@pointerInput
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
        }

        hud?.let { h ->
            Text(h, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // ---------- error ----------
        error?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2140))) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B8A), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Can't play this movie", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = Color(0xFFCBD0F0), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onBack) { Text("Back") }
                        Button(onClick = { attempt++ }) { Text("Try again") }
                    }
                }
            }
        }

        // ---------- controls ----------
        if (!pip && error == null) AnimatedVisibility(show, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))

                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text(if (prefs.hideNotif) "" else (movie?.displayTitle ?: ""), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (prefs.bandwidthSaver) { vm.set(K.bandwidthSaver, false); hud = "Data saver off" } else askSaver = true
                    }) {
                        Icon(if (prefs.bandwidthSaver) Icons.Default.DataSaverOn else Icons.Default.DataSaverOff, "Data saver",
                            tint = if (prefs.bandwidthSaver) Color(0xFF7CE0A5) else Color.White)
                    }
                    IconButton(onClick = { (activity as? MainActivity)?.enterPip() }) { Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                    IconButton(onClick = {
                        activity?.requestedOrientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
                    if (noSupportedAudio) Text("No supported audio track on this phone (e.g. DTS). Try another audio track.", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = scrub ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { scrub = it; touchTick++ },
                        onValueChangeFinished = { scrub?.let { player.seekTo((it * dur).toLong()) }; scrub = null }
                    )
                    Row {
                        Text(fmtTime(if (scrub != null) (scrub!! * dur).toLong() else pos), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (abA >= 0) Text("A${if (abB >= 0) "–B" else ""} repeat  ", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                        Text(fmtTime(dur), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CtrlBtn(Icons.Default.Subtitles, "Subtitles") { sheet = "subs" }
                        CtrlBtn(Icons.Default.Audiotrack, "Audio") { sheet = "audio" }
                        CtrlBtn(Icons.Default.Speed, "${speed}x") { sheet = "speed" }
                        CtrlBtn(aspectIcon(aspect), aspectLabel(aspect)) { aspect = nextAspect(aspect); hud = aspectHud(aspect); touchTick++ }
                        CtrlBtn(Icons.Default.MoreHoriz, "More") { sheet = "more" }
                    }
                }
            }
        }

        if (askSaver) AlertDialog(
            onDismissRequest = { askSaver = false },
            title = { Text("Turn on Data saver?") },
            text = {
                Text("This uses less data but lowers quality: Telegram is read in smaller steps, so a slow connection may buffer more often, " +
                    "and pictures are smaller. When a movie has several files, the smallest one is chosen the next time you press Play.")
            },
            confirmButton = { TextButton(onClick = { vm.set(K.bandwidthSaver, true); hud = "Data saver on"; askSaver = false }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { askSaver = false }) { Text("Cancel") } }
        )

        // ---------- sheets ----------
        if (!pip) when (sheet) {
            "subs" -> Sheet("Subtitles", { sheet = null }) {
                Opt("Off", !extOn && textOpts.none { it.selected }) { extOn = false; disableText() }
                textOpts.forEach { o -> Opt(o.label, !extOn && o.selected) { extOn = false; selectTrack(o) } }
                if (ext.isNotEmpty()) Opt("$extName (loaded file)", extOn) { extOn = true; disableText() }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Load subtitle file (.srt .vtt .ass)") }
                Text("Subtitle delay: $subDelay ms", Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> OutlinedButton(onClick = { subDelay += d }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { subDelay = 0 }) { Text("Reset") }
                }
                Text("Delay applies to loaded subtitle files. Positive = subtitles appear later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "audio" -> Sheet("Audio", { sheet = null }) {
                if (audioOpts.isEmpty()) Text("No selectable audio tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                audioOpts.forEach { o -> Opt(o.label + if (!o.supported) "  (not supported)" else "", o.selected, o.supported) { selectTrack(o) } }
                Text("Audio delay: $audioDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-100, 100, 500).forEach { d ->
                        OutlinedButton(onClick = { audioDelay = (audioDelay + d).coerceIn(0, 2000); PlayerManager.audioDelay.delayMs = audioDelay }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") }
                    }
                    TextButton(onClick = { audioDelay = 0; PlayerManager.audioDelay.delayMs = 0 }) { Text("Reset") }
                }
                Text("Use this when the voices are ahead of the picture (0–2000 ms).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "speed" -> Sheet("Playback speed", { sheet = null }) {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s -> Opt("${s}x", speed == s) { speed = s; player.setPlaybackSpeed(s) } }
            }
            "aspect" -> Sheet("Aspect ratio", { sheet = null }) {
                listOf("fit" to "Fit to screen", "fill" to "Stretch to fill", "crop" to "Crop (zoom to fill)", "16:9" to "16:9", "4:3" to "4:3").forEach { (k, l) -> Opt(l, aspect == k) { aspect = k } }
            }
            "quality" -> Sheet("Video quality", { sheet = null }) {
                Opt("Auto", true) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() }
                videoOpts.forEach { o -> Opt(o.label, o.selected, o.supported) { selectTrack(o) } }
                if (videoOpts.size <= 1) Text("This movie is a single-quality file, so there is nothing to switch. (Choices appear for adaptive streams.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "sleep" -> Sheet("Sleep timer", { sheet = null }) {
                Opt("Off", sleepAt == 0L) { sleepAt = 0 }
                listOf(15, 30, 45, 60, 90).forEach { m -> Opt("$m minutes", false) { sleepAt = System.currentTimeMillis() + m * 60_000L; vm.say("Sleep timer: $m min"); sheet = null } }
                Opt("End of movie", sleepAt == -1L) { sleepAt = -1; sheet = null }
            }
            "more" -> Sheet("More", { sheet = null }) {
                Opt("Set A (repeat start): ${if (abA >= 0) fmtTime(abA) else "—"}", abA >= 0) { abA = player.currentPosition; if (abB <= abA) abB = -1 }
                Opt("Set B (repeat end): ${if (abB >= 0) fmtTime(abB) else "—"}", abB >= 0) { if (abA >= 0 && player.currentPosition > abA) abB = player.currentPosition else vm.say("Set A first, then B later in the movie") }
                Opt("Clear A-B repeat", false) { abA = -1; abB = -1 }
                Opt("Aspect ratio…", false) { sheet = "aspect" }
                Opt("Data saver: ${if (prefs.bandwidthSaver) "ON" else "OFF"}", prefs.bandwidthSaver) {
                    sheet = null
                    if (prefs.bandwidthSaver) { vm.set(K.bandwidthSaver, false); hud = "Data saver off" } else askSaver = true
                }
                Opt("Sleep timer…", sleepAt != 0L) { sheet = "sleep" }
                Opt("Video quality…", false) { sheet = "quality" }
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

// Tap the aspect button to jump to the next mode (like MX Player / VLC); the icon and label change with it.
private val aspectModes = listOf("fit", "fill", "crop", "16:9", "4:3")
private fun nextAspect(a: String) = aspectModes[(aspectModes.indexOf(a) + 1) % aspectModes.size]
private fun aspectIcon(a: String): ImageVector = when (a) {
    "fill" -> Icons.Default.OpenInFull
    "crop" -> Icons.Default.Crop
    "16:9" -> Icons.Default.Crop169
    "4:3" -> Icons.Default.Crop54
    else -> Icons.Default.FitScreen
}
private fun aspectLabel(a: String) = when (a) { "fill" -> "Stretch"; "crop" -> "Crop"; "16:9" -> "16:9"; "4:3" -> "4:3"; else -> "Fit" }
private fun aspectHud(a: String) = when (a) {
    "fill" -> "Stretch to screen"; "crop" -> "Crop (fill screen)"; "16:9" -> "16:9"; "4:3" -> "4:3"; else -> "Fit to screen"
}   // = BRIGHTNESS_OVERRIDE_NONE: go back to the system brightness

@Composable
private fun CtrlBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth(0.92f).heightIn(max = 380.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

@Composable
private fun Opt(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = if (enabled) onClick else null, enabled = enabled)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

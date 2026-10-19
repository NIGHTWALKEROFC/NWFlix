package app.nwmovie.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nwmovie.AppState
import app.nwmovie.MainActivity
import app.nwmovie.MainViewModel
import app.nwmovie.ServiceLocator
import app.nwmovie.data.CacheManager
import app.nwmovie.data.K
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.db.Movie
import app.nwmovie.data.fileTags
import app.nwmovie.data.groupKey
import app.nwmovie.player.LocalStreamServer
import app.nwmovie.util.findActivity
import app.nwmovie.util.fmtSize
import app.nwmovie.util.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** VLC engine player: opens the formats the standard player can't (AVI, WMV, FLV, DTS, AC3…), with the same controls. */
@Composable
fun VlcPlayerScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onNext: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val prefs by vm.prefs.collectAsState()
    val pip by AppState.inPip.collectAsState()
    val config = LocalConfiguration.current
    val scope = rememberCoroutineScope()

    val libVlc = remember { LibVLC(ctx, arrayListOf("--network-caching=3000", "--file-caching=1500", "--no-drop-late-frames", "--no-skip-frames")) }
    val mp = remember { MediaPlayer(libVlc) }

    var movie by remember { mutableStateOf<Movie?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>("Opening…") }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(prefs.speed) }
    var show by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var locked by remember { mutableStateOf(false) }
    var manualRotate by remember { mutableStateOf(false) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var versions by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var spuTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var curAudio by remember { mutableIntStateOf(-1) }
    var curSpu by remember { mutableIntStateOf(-1) }
    var subDelay by remember { mutableIntStateOf(prefs.subDelayMs) }
    var audioDelay by remember { mutableIntStateOf(prefs.audioDelayMs) }
    var volPct by remember { mutableFloatStateOf(if (prefs.rememberVolume && prefs.savedVolume >= 5) prefs.savedVolume.toFloat().coerceIn(5f, 200f) else 100f) }
    var bright by remember { mutableFloatStateOf(if (prefs.rememberBrightness && prefs.savedBrightness >= 0.05f) prefs.savedBrightness else 0.6f) }
    var streamedFileId by remember { mutableIntStateOf(0) }
    val lowAtStart = remember { CacheManager.isLowStorage(ctx) }
    var appliedDelays by remember { mutableStateOf(false) }

    fun readTracks() {
        audioTracks = mp.getAudioTracks()?.toList() ?: emptyList()
        spuTracks = mp.getSpuTracks()?.toList() ?: emptyList()
        curAudio = mp.getAudioTrack()
        curSpu = mp.getSpuTrack()
    }

    // ---------- start ----------
    LaunchedEffect(id) {
        val p = ServiceLocator.prefs.value
        val m = ServiceLocator.db.movies().get(id)
        if (m == null) { error = "Movie not found."; loading = null; return@LaunchedEffect }
        movie = m
        val local = ServiceLocator.downloads.localPath(id)
        val uri: Uri
        if (local != null) uri = Uri.fromFile(File(local)) else {
            NetPolicy.streamBlock(p)?.let { error = it; loading = null; return@LaunchedEffect }
            if (lowAtStart && !ServiceLocator.downloads.hasActive()) try { ServiceLocator.tg.optimizeTemp(0, 0, all = true) } catch (_: Exception) { }
            val found = resolveTgFile(m)
            if (found == null) { error = "This file is no longer available in Telegram."; loading = null; return@LaunchedEffect }
            val (src, tf) = found
            if (src.id == m.id && tf.id != m.fileId) ServiceLocator.db.movies().setFileId(m.id, tf.id)
            streamedFileId = tf.id
            uri = Uri.parse(LocalStreamServer.urlFor(tf.id))
        }
        val startMs = if (AppState.switchTo == id) { AppState.switchTo = null; AppState.switchMs }
        else if (p.resume && m.positionMs > 5000 && m.durationMs > 0 && m.positionMs < m.durationMs * 0.95) m.positionMs else 0L
        val media = Media(libVlc, uri)
        media.setHWDecoderEnabled(p.hwAccel, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mp.setMedia(media)
        media.release()
        mp.setVolume(volPct.roundToInt())
        mp.play()
    }

    DisposableEffect(mp) {
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaying = true; buffering = false; loading = null; readTracks(); mp.setRate(speed)
                    if (!appliedDelays) {
                        appliedDelays = true
                        if (subDelay != 0) mp.setSpuDelay(subDelay * 1000L)
                        if (audioDelay != 0) mp.setAudioDelay(audioDelay * 1000L)
                    }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.ESAdded -> readTracks()
                MediaPlayer.Event.Vout -> try {
                    val t = mp.currentVideoTrack
                    // a tall (phone-recorded) video opens upright
                    if (t != null && !manualRotate && t.height > t.width) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } catch (_: Throwable) { }
                MediaPlayer.Event.LengthChanged -> dur = ev.lengthChanged
                MediaPlayer.Event.TimeChanged -> pos = ev.timeChanged
                MediaPlayer.Event.EncounteredError -> { error = "The VLC engine could not play this file. It may be damaged, or Telegram stopped sending data."; loading = null }
                MediaPlayer.Event.EndReached -> {
                    isPlaying = false
                    vm.saveProgress(id, dur, dur)
                    if (sleepAt == -1L) { sleepAt = 0; vm.say("Sleep timer: stopped at the end") }
                    else if (ServiceLocator.prefs.value.autoNext) vm.nextAfter(id)?.let { onNext(it) }
                }
            }
        }
        onDispose {
            AppState.playerOpen = false; AppState.playerPlaying = false
            if (dur > 0 && pos > 0) vm.saveProgress(id, pos, dur)
            try { mp.setEventListener(null); mp.stop(); mp.detachViews(); mp.release(); libVlc.release() } catch (_: Exception) { }
            val mode = ServiceLocator.prefs.value.clearAfterWatch
            val fid = streamedFileId
            if (fid != 0 && (mode == "always" || lowAtStart || (mode == "auto" && CacheManager.isLowStorage(ctx)))) {
                ServiceLocator.scope.launch { delay(1500); if (!ServiceLocator.tg.isPinned(fid)) ServiceLocator.tg.deleteFile(fid) }
            }
        }
    }
    LaunchedEffect(isPlaying) { AppState.playerPlaying = isPlaying }
    LaunchedEffect(Unit) { AppState.playerOpen = true }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (dur > 0 && pos > 0) vm.saveProgress(id, pos, dur); if (isPlaying) vm.addWatch(id, 5000) } }

    // ---------- sleep timer ----------
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            mp.pause(); sleepAt = 0; vm.say("Sleep timer: paused")
        }
    }

    // ---------- window: bars, orientation, brightness ----------
    DisposableEffect(Unit) {
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (prefs.fullscreen && ctrl != null) {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (prefs.rememberBrightness && prefs.savedBrightness >= 0.05f && window != null) window.attributes = window.attributes.apply { screenBrightness = bright }
        onDispose {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { it.attributes = it.attributes.apply { screenBrightness = -1f } }
        }
    }

    // background playback off: pause when the app leaves the screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !ServiceLocator.prefs.value.bgPlayback && !AppState.inPip.value) mp.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(show, isPlaying, sheet, touchTick) { if (show && isPlaying && sheet == null) { delay(3500); show = false } }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(sheet) {
        if (sheet == "versions") {
            val cur = movie
            versions = if (cur == null) emptyList() else ServiceLocator.db.movies().allOnce().filter {
                it.groupKey() == cur.groupKey() && it.id != cur.id && it.part == 0 && (!cur.isSeries || (it.season == cur.season && it.episode == cur.episode))
            }.sortedByDescending { it.size }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) {
                try { File(ctx.cacheDir, "vlc_sub_${System.currentTimeMillis()}.srt").also { out -> ctx.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } } } catch (e: Exception) { null }
            }
            if (f != null) { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true); delay(500); readTracks(); vm.say("Subtitles loaded") } else vm.say("Couldn't read that subtitle file")
        }
    }

    fun seekBy(ms: Long) { mp.setTime((mp.getTime() + ms).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> VLCVideoLayout(c).also { mp.attachViews(it, null, true, false) } }, modifier = Modifier.fillMaxSize())

        // ---------- gestures ----------
        Box(Modifier.fillMaxSize()
            .pointerInput(prefs.doubleTapSec, locked) {
                if (locked) {
                    detectTapGestures(onTap = { show = !show; touchTick++ })
                } else detectTapGestures(
                    onPress = {
                        var boost = false
                        val job = scope.launch { delay(450); boost = true; mp.setRate(2f); hud = "2× speed" }
                        tryAwaitRelease()
                        job.cancel()
                        if (boost) { mp.setRate(speed); hud = null }
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
                var left = true
                var mode = 0
                var acc = 0f
                var startPos = 0L
                var pending = -1L
                detectDragGestures(
                    onDragStart = { o -> left = o.x < size.width / 2f; mode = 0; acc = 0f; startPos = mp.getTime(); pending = -1 },
                    onDragEnd = {
                        if (mode == 1 && pending >= 0) mp.setTime(pending)
                        if (mode == 2) {
                            if (left && prefs.rememberBrightness) vm.set(K.savedBrightness, bright)
                            else if (!left && prefs.rememberVolume) vm.set(K.savedVolume, volPct.roundToInt())
                        }
                        pending = -1; mode = 0
                    },
                    onDragCancel = { pending = -1; mode = 0 }
                ) { change, d ->
                    change.consume()
                    if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 1 else 2
                    if (mode == 1 && prefs.seekGesture && dur > 0) {
                        acc += d.x / size.width * 120_000f
                        val t = (startPos + acc).toLong().coerceIn(0, dur)
                        pending = t
                        hud = "${fmtTime(t)}  (${if (acc >= 0) "+" else "−"}${fmtTime(abs(acc).toLong())})"
                    } else if (mode == 2) {
                        val delta = -d.y / size.height * 1.3f
                        if (left && prefs.brightnessGesture) {
                            bright = (bright + delta).coerceIn(0.05f, 1f)
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
                            hud = "Brightness ${(bright * 100).roundToInt()}%"
                        } else if (!left && prefs.volumeGesture) {
                            volPct = (volPct + delta * 200f).coerceIn(0f, 200f)
                            mp.setVolume(volPct.roundToInt())
                            hud = "Volume ${volPct.roundToInt()}%" + if (volPct > 100f) "  (boost)" else ""
                        }
                    }
                }
            })

        if (error == null && (loading != null || buffering) && !pip) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(Modifier.height(8.dp))
            Text(loading ?: "Loading…", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
        hud?.let { h -> Text(h, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) }

        error?.let { msg ->
            Card(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 420.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2140))) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B8A), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = Color(0xFFCBD0F0), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onBack) { Text("Back") }
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
                    Text(if (prefs.hideNotif) "" else playerTitle(movie, 1, 0), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
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
                    FilledIconButton(onClick = { if (mp.isPlaying()) mp.pause() else mp.play(); touchTick++ }, Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = 0.18f))) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play / pause", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { seekBy(10_000); touchTick++ }, Modifier.size(52.dp)) { Icon(Icons.Default.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Slider(value = scrub ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, onValueChange = { scrub = it; touchTick++ },
                        onValueChangeFinished = { scrub?.let { mp.setTime((it * dur).toLong()) }; scrub = null })
                    Row {
                        Text(fmtTime(if (scrub != null) (scrub!! * dur).toLong() else pos), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(fmtTime(dur), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PlayerBtn(Icons.Default.Subtitles, "Subtitles") { sheet = "subs" }
                        PlayerBtn(Icons.Default.Audiotrack, "Audio") { sheet = "audio" }
                        PlayerBtn(Icons.Default.Speed, "${speed}x") { sheet = "speed" }
                        PlayerBtn(Icons.Default.MoreHoriz, "More") { sheet = "more" }
                    }
                }
            }
        }

        // ---------- sheets ----------
        if (!pip) when (sheet) {
            "subs" -> PlayerSheet("Subtitles", { sheet = null }) {
                PlayerOpt("Off", curSpu == -1) { mp.setSpuTrack(-1); curSpu = -1 }
                spuTracks.filter { it.id != -1 }.forEach { t -> PlayerOpt(t.name ?: "Track ${t.id}", curSpu == t.id) { mp.setSpuTrack(t.id); curSpu = t.id } }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Load subtitle file (.srt .vtt .ass)") }
                Text("Subtitle delay: $subDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> OutlinedButton(onClick = { subDelay += d; mp.setSpuDelay(subDelay * 1000L) }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { subDelay = 0; mp.setSpuDelay(0) }) { Text("Reset") }
                }
            }
            "audio" -> PlayerSheet("Audio", { sheet = null }) {
                if (audioTracks.none { it.id != -1 }) Text("No selectable audio tracks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                audioTracks.filter { it.id != -1 }.forEach { t -> PlayerOpt(t.name ?: "Track ${t.id}", curAudio == t.id) { mp.setAudioTrack(t.id); curAudio = t.id } }
                Text("Audio delay: $audioDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> OutlinedButton(onClick = { audioDelay += d; mp.setAudioDelay(audioDelay * 1000L) }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { audioDelay = 0; mp.setAudioDelay(0) }) { Text("Reset") }
                }
                Text("Volume goes up to 200 %: swipe up on the right side of the screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "speed" -> PlayerSheet("Playback speed", { sheet = null }) {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s -> PlayerOpt("${s}x", speed == s) { speed = s; mp.setRate(s) } }
            }
            "versions" -> PlayerSheet("Switch version", { sheet = null }) {
                if (versions.isEmpty()) Text("No other version of this movie was found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                versions.forEach { v ->
                    val t = fileTags(v).joinToString("  •  ").ifEmpty { "Video file" }
                    PlayerOpt("$t  (${fmtSize(v.size)})", false) {
                        AppState.switchTo = v.id; AppState.switchMs = mp.getTime()
                        sheet = null; onNext(v.id)
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
                PlayerAction(Icons.Default.PlayCircle, "Play with the standard player") {
                    AppState.forceStandard = id; AppState.switchTo = id; AppState.switchMs = mp.getTime()
                    sheet = null; onNext(id)
                }
                val m = movie
                Text("Info", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text(buildString {
                    append("VLC engine\n")
                    if (!prefs.hideNotif) append(m?.fileName?.ifBlank { null } ?: "Telegram video").append('\n')
                    if (m != null) append(fmtSize(m.size))
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

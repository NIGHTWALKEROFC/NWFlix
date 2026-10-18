package app.nwmovie.ui.screens

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.nwmovie.AppState
import app.nwmovie.MainViewModel
import app.nwmovie.ServiceLocator
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.db.Movie
import app.nwmovie.player.LocalStreamServer
import app.nwmovie.util.findActivity
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

private val vlcAspects = listOf("fit", "fill", "crop", "16:9", "4:3")

/**
 * Compatibility player (VLC engine): plays practically every video/audio format (AVI, WMV, FLV, DTS, AC3, old codecs…)
 * with software decoding when the phone's hardware cannot. Used automatically when the normal player cannot open a file.
 */
@Composable
fun VlcPlayerScreen(vm: MainViewModel, id: String, onBack: () -> Unit, onNext: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val prefs by vm.prefs.collectAsState()
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
    var speed by remember { mutableFloatStateOf(1f) }
    var aspect by remember { mutableStateOf("fit") }
    var show by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var audioTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var spuTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var curAudio by remember { mutableIntStateOf(-1) }
    var curSpu by remember { mutableIntStateOf(-1) }
    var subDelay by remember { mutableIntStateOf(0) }
    var audioDelay by remember { mutableIntStateOf(0) }
    var volPct by remember { mutableFloatStateOf(if (prefs.rememberVolume && prefs.savedVolume >= 5) prefs.savedVolume.toFloat().coerceIn(5f, 200f) else 100f) }
    var bright by remember { mutableFloatStateOf(if (prefs.rememberBrightness && prefs.savedBrightness >= 0.05f) prefs.savedBrightness else 0.6f) }

    fun readTracks() {
        audioTracks = mp.getAudioTracks()?.toList() ?: emptyList()
        spuTracks = mp.getSpuTracks()?.toList() ?: emptyList()
        curAudio = mp.getAudioTrack()
        curSpu = mp.getSpuTrack()
    }
    fun applyAspect(a: String) {
        mp.setVideoScale(when (a) {
            "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
            "crop" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            "16:9" -> MediaPlayer.ScaleType.SURFACE_16_9
            "4:3" -> MediaPlayer.ScaleType.SURFACE_4_3
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        })
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
            val tf = ServiceLocator.tg.refreshFile(m)
            if (tf == null) { error = "This file is no longer available in Telegram."; loading = null; return@LaunchedEffect }
            if (tf.id != m.fileId) ServiceLocator.db.movies().setFileId(m.id, tf.id)
            uri = Uri.parse(LocalStreamServer.urlFor(tf.id))
        }
        val startMs = if (AppState.switchTo == id) { AppState.switchTo = null; AppState.switchMs }
        else if (p.resume && m.positionMs > 5000 && m.durationMs > 0 && m.positionMs < m.durationMs * 0.95) m.positionMs else 0L
        val media = Media(libVlc, uri)
        media.setHWDecoderEnabled(p.hwAccel, false)             // hardware decoding when possible, automatic software fallback
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mp.setMedia(media)
        media.release()
        mp.setVolume(volPct.roundToInt())
        mp.play()
    }

    DisposableEffect(mp) {
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> { isPlaying = true; buffering = false; loading = null; readTracks(); mp.setRate(speed) }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.ESAdded -> readTracks()
                MediaPlayer.Event.LengthChanged -> dur = ev.lengthChanged
                MediaPlayer.Event.TimeChanged -> pos = ev.timeChanged
                MediaPlayer.Event.EncounteredError -> { error = "The VLC engine could not play this file. It may be damaged, or Telegram stopped sending data."; loading = null }
                MediaPlayer.Event.EndReached -> {
                    isPlaying = false
                    vm.saveProgress(id, dur, dur)
                    if (ServiceLocator.prefs.value.autoNext) vm.nextAfter(id)?.let { onNext(it) }
                }
            }
        }
        onDispose {
            AppState.playerOpen = false; AppState.playerPlaying = false
            if (dur > 0 && pos > 0) vm.saveProgress(id, pos, dur)
            try { mp.setEventListener(null); mp.stop(); mp.detachViews(); mp.release(); libVlc.release() } catch (_: Exception) { }
        }
    }
    LaunchedEffect(isPlaying) { AppState.playerPlaying = isPlaying }
    LaunchedEffect(Unit) { AppState.playerOpen = true }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (dur > 0 && pos > 0) vm.saveProgress(id, pos, dur); if (isPlaying) vm.addWatch(id, 5000) } }

    // full screen + landscape while watching; brightness restored afterwards
    DisposableEffect(Unit) {
        val window = activity?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        if (prefs.rememberBrightness && prefs.savedBrightness >= 0.05f && window != null) window.attributes = window.attributes.apply { screenBrightness = bright }
        onDispose {
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { it.attributes = it.attributes.apply { screenBrightness = -1f } }
        }
    }
    LaunchedEffect(show, isPlaying, sheet, touchTick) { if (show && isPlaying && sheet == null) { delay(3500); show = false } }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }

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

        // gestures: tap = controls, double tap = seek, vertical swipe = brightness (left) / volume 0-200 % (right)
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { show = !show; touchTick++ },
                    onDoubleTap = { o -> if (o.x < size.width / 2f) { seekBy(-10_000); hud = "−10s" } else { seekBy(10_000); hud = "+10s" } }
                )
            }
            .pointerInput(Unit) {
                var left = true
                var mode = 0
                detectDragGestures(
                    onDragStart = { o -> left = o.x < size.width / 2f; mode = 0 },
                    onDragEnd = { if (mode == 2) { if (left) vm.set(app.nwmovie.data.K.savedBrightness, bright) else vm.set(app.nwmovie.data.K.savedVolume, volPct.roundToInt()) }; mode = 0 }
                ) { change, d ->
                    change.consume()
                    if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 1 else 2
                    if (mode == 1 && dur > 0) { seekBy((d.x / size.width * 120_000f).toLong()); hud = fmtTime(mp.getTime()) }
                    else if (mode == 2) {
                        val delta = -d.y / size.height * 1.3f
                        if (left) {
                            bright = (bright + delta).coerceIn(0.05f, 1f)
                            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = bright } }
                            hud = "Brightness ${(bright * 100).roundToInt()}%"
                        } else {
                            volPct = (volPct + delta * 200f).coerceIn(0f, 200f)
                            mp.setVolume(volPct.roundToInt())
                            hud = "Volume ${volPct.roundToInt()}%"
                        }
                    }
                }
            })

        if (error == null && (loading != null || buffering)) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
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

        if (show && error == null) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(100.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Text(if (prefs.hideNotif) "" else ((movie?.displayTitle ?: "") + if (movie?.isSeries == true) "  S%02dE%02d".format(movie?.season ?: 0, movie?.episode ?: 0) else ""),
                        color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("VLC engine", color = Color(0xFF9CB4FF), style = MaterialTheme.typography.labelMedium)
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
                        VBtn(Icons.Default.Subtitles, "Subtitles") { sheet = "subs" }
                        VBtn(Icons.Default.Audiotrack, "Audio") { sheet = "audio" }
                        VBtn(Icons.Default.Speed, "${speed}x") { sheet = "speed" }
                        VBtn(Icons.Default.AspectRatio, when (aspect) { "fill" -> "Stretch"; "crop" -> "Crop"; "16:9" -> "16:9"; "4:3" -> "4:3"; else -> "Fit" }) {
                            aspect = vlcAspects[(vlcAspects.indexOf(aspect) + 1) % vlcAspects.size]; applyAspect(aspect); hud = "Aspect: $aspect"; touchTick++
                        }
                    }
                }
            }
        }

        when (sheet) {
            "subs" -> VSheet("Subtitles", { sheet = null }) {
                VOpt("Off", curSpu == -1) { mp.setSpuTrack(-1); curSpu = -1 }
                spuTracks.filter { it.id != -1 }.forEach { t -> VOpt(t.name ?: "Track ${t.id}", curSpu == t.id) { mp.setSpuTrack(t.id); curSpu = t.id } }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Load subtitle file (.srt .vtt .ass)") }
                Text("Subtitle delay: $subDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> OutlinedButton(onClick = { subDelay += d; mp.setSpuDelay(subDelay * 1000L) }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { subDelay = 0; mp.setSpuDelay(0) }) { Text("Reset") }
                }
            }
            "audio" -> VSheet("Audio", { sheet = null }) {
                audioTracks.filter { it.id != -1 }.forEach { t -> VOpt(t.name ?: "Track ${t.id}", curAudio == t.id) { mp.setAudioTrack(t.id); curAudio = t.id } }
                Text("Audio delay: $audioDelay ms", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-500, -100, 100, 500).forEach { d -> OutlinedButton(onClick = { audioDelay += d; mp.setAudioDelay(audioDelay * 1000L) }, contentPadding = PaddingValues(8.dp)) { Text(if (d > 0) "+$d" else "$d") } }
                    TextButton(onClick = { audioDelay = 0; mp.setAudioDelay(0) }) { Text("Reset") }
                }
                Text("Volume goes up to 200 %: swipe up on the right side of the screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "speed" -> VSheet("Playback speed", { sheet = null }) {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { s -> VOpt("${s}x", speed == s) { speed = s; mp.setRate(s) } }
            }
            "more" -> VSheet("VLC engine", { sheet = null }) {
                Text("This engine plays almost every format (AVI, WMV, FLV, DTS, AC3…). Use the back button to return.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun VBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun VSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
private fun VOpt(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

package app.nwmovie.ui.screens

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import app.nwmovie.R
import app.nwmovie.ServiceLocator
import app.nwmovie.data.CacheManager
import app.nwmovie.data.NetPolicy
import app.nwmovie.data.db.Movie
import app.nwmovie.data.telegram.TelegramDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.random.Random

/**
 * Muted preview on a movie page: a few seconds from a random scene, then another one.
 * On mobile data it waits for a tap on the Preview button (or the setting that allows it).
 * Never with Data saver or when the phone is low on space.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewVideo(movie: Movie, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs by ServiceLocator.prefs.collectAsState()
    val metered by NetPolicy.metered.collectAsState()
    var tapped by remember(movie.id) { mutableStateOf(false) }
    var player by remember(movie.id) { mutableStateOf<ExoPlayer?>(null) }
    var visible by remember(movie.id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "preview")
    val needTap = metered && !prefs.previewMobile && !tapped

    LaunchedEffect(movie.id, needTap) {
        if (needTap) return@LaunchedEffect
        delay(1000)                                   // skip it while the user is just scrolling past
        val p = ServiceLocator.prefs.value
        if (!p.previews || p.bandwidthSaver) return@LaunchedEffect
        val local = ServiceLocator.downloads.localPath(movie.id)
        val uri: Uri
        if (local != null) uri = Uri.fromFile(File(local)) else {
            if (NetPolicy.streamBlock(p) != null || CacheManager.isLowStorage(ctx)) return@LaunchedEffect
            val tf = ServiceLocator.tg.refreshFile(movie) ?: return@LaunchedEffect
            uri = Uri.parse("tg://file/${tf.id}")
        }
        val exo = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(ctx, TelegramDataSource.Factory(ServiceLocator.tg) { 8L * 1024 * 1024 }),
                    DefaultExtractorsFactory()
                )
            ).build()
        var failed = false
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        })
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        player = exo

        suspend fun waitPlaying(ms: Long): Boolean = withTimeoutOrNull(ms) {
            while (!(exo.playbackState == Player.STATE_READY && exo.isPlaying) && !failed) delay(150)
            !failed
        } ?: false

        try {
            val ready = withTimeoutOrNull(30_000) {
                while (exo.playbackState != Player.STATE_READY && !failed) delay(200)
                !failed
            } ?: false
            if (!ready) return@LaunchedEffect
            val total = exo.duration
            val canSeek = total > 60_000 && exo.isCurrentMediaItemSeekable
            var misses = 0
            while (isActive && !failed) {
                visible = false
                delay(250)
                if (canSeek) exo.seekTo((total * Random.nextDouble(0.12, 0.85)).toLong())
                exo.play()
                var ok = waitPlaying(20_000)
                if (!ok && canSeek && misses++ < 1) {        // that spot would not load: go back to the start once
                    exo.seekTo(0); exo.play(); ok = waitPlaying(15_000)
                }
                if (!ok) break
                visible = true
                delay(if (canSeek) 6_000 else 15_000)
                if (!canSeek) break
            }
        } finally {
            visible = false
            player = null
            exo.release()
        }
    }

    // stop while the app is in the background
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player?.pause() else if (e == Lifecycle.Event.ON_START) player?.play() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Box(modifier) {
        val cur = player
        if (cur != null) AndroidView(
            factory = { c -> (LayoutInflater.from(c).inflate(R.layout.preview_player, null) as PlayerView).also { it.player = cur } },
            update = { it.player = cur },
            modifier = Modifier.fillMaxSize().alpha(alpha)
        )
        if (needTap && prefs.previews && !prefs.bandwidthSaver) Row(
            Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))
                .clickable { tapped = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text("Preview", color = Color.White)
        }
    }
}

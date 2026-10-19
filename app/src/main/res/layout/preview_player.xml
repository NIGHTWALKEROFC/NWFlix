package app.nwmovie.ui.screens

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
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
 * Muted preview on a movie page: a few seconds from a random scene, then another random scene, and so on.
 * Only on Wi-Fi, never with Data saver or when the phone is low on space. Stays invisible until the first frame plays.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewVideo(movie: Movie, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var player by remember(movie.id) { mutableStateOf<ExoPlayer?>(null) }
    var visible by remember(movie.id) { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "preview")

    LaunchedEffect(movie.id) {
        delay(1200)                                   // skip it while the user is just scrolling past
        val p = ServiceLocator.prefs.value
        if (!p.previews || p.bandwidthSaver) return@LaunchedEffect
        val local = ServiceLocator.downloads.localPath(movie.id)
        val uri: Uri
        if (local != null) uri = Uri.fromFile(File(local)) else {
            if (NetPolicy.metered.value || NetPolicy.streamBlock(p) != null || CacheManager.isLowStorage(ctx)) return@LaunchedEffect
            val tf = ServiceLocator.tg.refreshFile(movie) ?: return@LaunchedEffect
            uri = Uri.parse("tg://file/${tf.id}")
        }
        val exo = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultDataSource.Factory(ctx, TelegramDataSource.Factory(ServiceLocator.tg) { 8L * 1024 * 1024 }),
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                )
            ).build()
        var failed = false
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed = true }
        })
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        player = exo
        try {
            val ready = withTimeoutOrNull(25_000) {
                while (exo.playbackState != Player.STATE_READY && !failed) delay(200)
                !failed
            } ?: false
            if (!ready || exo.duration <= 0) return@LaunchedEffect
            while (isActive && !failed) {
                val d = exo.duration
                exo.seekTo((d * Random.nextDouble(0.12, 0.85)).toLong())
                exo.play()
                val playing = withTimeoutOrNull(20_000) {
                    while (!exo.isPlaying && !failed) delay(150)
                    !failed
                } ?: false
                if (!playing) break
                visible = true
                delay(6_000)
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

    val cur = player
    if (cur != null) AndroidView(
        factory = { c -> (LayoutInflater.from(c).inflate(R.layout.preview_player, null) as PlayerView).also { it.player = cur } },
        update = { it.player = cur },
        modifier = modifier.alpha(alpha)
    )
}

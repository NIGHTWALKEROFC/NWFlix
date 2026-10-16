package app.nwmovie.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.nwmovie.ServiceLocator
import app.nwmovie.data.db.Movie
import app.nwmovie.data.telegram.TelegramDataSource
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Owns the single ExoPlayer. The PlaybackService wraps it in a MediaSession so playback
 * continues in the background (with a notification) and works with headset/lock-screen controls.
 */
@UnstableApi
object PlayerManager {
    val audioDelay = AudioDelayProcessor()
    private var exo: ExoPlayer? = null
    private var controller: ListenableFuture<MediaController>? = null
    val sleepAtMs = MutableStateFlow(0L)    // 0 = off, -1 = end of movie, else epoch millis

    fun player(): ExoPlayer = exo ?: create()

    /** Builds a fresh player so the latest settings (decoder choice, buffering) apply. */
    fun create(): ExoPlayer {
        release()
        val ctx = ServiceLocator.app
        val p = ServiceLocator.prefs.value

        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(audioDelay)).build()
        }.setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val list = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (p.hwAccel) list else list.sortedByDescending { it.softwareOnly }
            }

        // Telegram-aware data source: tg:// URIs are streamed through TDLib, everything else is normal.
        val window = { if (ServiceLocator.prefs.value.bandwidthSaver) 32L * 1024 * 1024 else 128L * 1024 * 1024 }
        val dataSources = DefaultDataSource.Factory(ctx, TelegramDataSource.Factory(ServiceLocator.tg, window))
        val sourceFactory = DefaultMediaSourceFactory(dataSources, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))

        // Start quickly (1.5 s), recover quickly after a stall (3 s), keep a healthy cushion ahead.
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, if (p.bandwidthSaver) 30_000 else 75_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(sourceFactory)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        var params = player.trackSelectionParameters.buildUpon()
        if (p.audioLang.isNotEmpty()) params = params.setPreferredAudioLanguage(p.audioLang)
        if (p.subLang.isNotEmpty()) params = params.setPreferredTextLanguage(p.subLang).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        else params = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        if (p.maxQuality > 0) params = params.setMaxVideoSize(p.maxQuality * 16 / 9, p.maxQuality)
        player.trackSelectionParameters = params.build()
        player.setPlaybackSpeed(p.speed)
        audioDelay.delayMs = p.audioDelayMs
        exo = player
        return player
    }

    fun open(m: Movie, startMs: Long, localPath: String?, fileId: Int?) {
        val player = player()
        val hide = ServiceLocator.prefs.value.hideNotif
        val meta = MediaMetadata.Builder().setTitle(if (hide) "NWMovie" else m.displayTitle).build()
        val item = if (localPath != null) {
            MediaItem.Builder().setUri(Uri.fromFile(File(localPath))).setMediaMetadata(meta).build()
        } else {
            MediaItem.Builder().setUri(Uri.parse("tg://file/$fileId")).setMediaMetadata(meta)
                .setMimeType(guessMime(m)).build()
        }
        player.setMediaItem(item, startMs)
        player.prepare()
        player.playWhenReady = true
        connectService()
    }

    private fun guessMime(m: Movie): String? = when {
        m.fileName.endsWith(".mkv", true) -> MimeTypes.VIDEO_MATROSKA
        m.fileName.endsWith(".webm", true) -> MimeTypes.VIDEO_WEBM
        m.fileName.endsWith(".mp4", true) || m.fileName.endsWith(".m4v", true) || m.fileName.endsWith(".mov", true) -> MimeTypes.VIDEO_MP4
        else -> null   // let ExoPlayer sniff the container
    }

    private fun connectService() {
        if (controller != null) return
        val ctx = ServiceLocator.app
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        controller = MediaController.Builder(ctx, token).buildAsync()
    }

    fun releaseIfCurrent(p: ExoPlayer) { if (exo === p) release() }

    fun release() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        exo?.release()
        exo = null
        sleepAtMs.value = 0
    }
}

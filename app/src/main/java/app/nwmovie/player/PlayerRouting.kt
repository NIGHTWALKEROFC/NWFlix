package app.nwmovie.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/** Decides when the built-in (ExoPlayer) engine is not enough and the VLC engine should take over. */
@UnstableApi
object PlayerRouting {
    /** Containers / old codecs ExoPlayer cannot open at all. */
    private val vlcOnly = setOf("avi", "wmv", "asf", "flv", "f4v", "mpg", "mpeg", "vob", "rmvb", "rm", "divx", "ogm", "m2ts", "mts", "dat", "xvid")

    fun preferVlc(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in vlcOnly

    fun isFormatError(code: Int): Boolean = code in setOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    )
}

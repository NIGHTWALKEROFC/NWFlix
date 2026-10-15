package app.nwmovie.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Delays the audio by a number of milliseconds (fixes "voice is ahead of the picture").
 * Works for 16-bit PCM, which is what the decoders output.
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {
    @Volatile var delayMs: Int = 0
    private var ring = ByteArray(0)
    private var rp = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val d = delayMs
        if (d <= 0) {
            if (ring.isNotEmpty()) { ring = ByteArray(0); rp = 0 }
            out.put(inputBuffer)
        } else {
            val fmt = inputAudioFormat
            val want = (fmt.sampleRate.toLong() * d / 1000).toInt() * fmt.bytesPerFrame
            if (want != ring.size) { ring = ByteArray(want); rp = 0 }
            while (inputBuffer.hasRemaining()) {
                val b = inputBuffer.get()
                out.put(ring[rp])
                ring[rp] = b
                rp++
                if (rp == ring.size) rp = 0
            }
        }
        out.flip()
    }

    override fun onFlush() { ring = ByteArray(0); rp = 0 }
    override fun onReset() { ring = ByteArray(0); rp = 0 }
}

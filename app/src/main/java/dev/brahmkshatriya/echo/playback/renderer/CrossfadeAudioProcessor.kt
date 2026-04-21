package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class CrossfadeAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled = false
    @Volatile var crossfadeDurationMs = 5000

    private val fadeInFramesRemaining = AtomicLong(0)
    private val fadeOutFramesRemaining = AtomicLong(0)
    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET

    fun onTrackStart() {
        fadeOutFramesRemaining.set(0)
        fadeInFramesRemaining.set(if (enabled) crossfadeFrames() else 0L)
    }

    fun onFadeOutStart() {
        if (enabled) fadeOutFramesRemaining.set(crossfadeFrames())
    }

    fun cancelFades() {
        fadeInFramesRemaining.set(0)
        fadeOutFramesRemaining.set(0)
    }

    private fun crossfadeFrames(): Long {
        val fmt = configuredFormat
        if (fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) return 0L
        return fmt.sampleRate.toLong() * crossfadeDurationMs / 1000L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val fi = fadeInFramesRemaining.get()
        val fo = fadeOutFramesRemaining.get()
        val output = replaceOutputBuffer(inputBuffer.remaining())

        val fmt = configuredFormat
        if (!enabled || (fi == 0L && fo == 0L) || fmt.sampleRate <= 0 || fmt.encoding != C.ENCODING_PCM_16BIT) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val total = crossfadeFrames()
        if (total == 0L) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channelCount = fmt.channelCount
        var currentFi = fi
        var currentFo = fo

        while (inputBuffer.remaining() >= channelCount * 2) {
            var gain = 1f
            if (currentFi > 0) {
                gain *= 1f - currentFi.toFloat() / total.toFloat()
                currentFi--
            }
            if (currentFo > 0) {
                gain *= currentFo.toFloat() / total.toFloat()
                currentFo--
            }
            repeat(channelCount) {
                val sample = inputBuffer.short.toInt()
                output.putShort((sample * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
        }

        fadeInFramesRemaining.set(currentFi)
        fadeOutFramesRemaining.set(currentFo)
        output.flip()
    }
}

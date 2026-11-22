package com.anisync.android.presentation.components

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.breakfastquay.rubberband.RubberBandStretcher
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Real-time Rubber Band R2 time-stretcher with pitch preserved.
 *
 * Keep the Media3 sink on integer PCM: enabling float output can bypass Media3's normal
 * audio-processing path. Convert to float only inside Rubber Band, then convert back to the
 * same PCM encoding that entered this processor.
 */
@UnstableApi
internal class RubberBandAudioProcessor : BaseAudioProcessor() {
    @Volatile private var requestedSpeed = 1f
    private var sampleRate = 0
    private var channels = 0
    private var inputEncoding = C.ENCODING_INVALID
    private var stretcher: RubberBandStretcher? = null

    fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed.coerceIn(0.5f, 4f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        inputEncoding = inputAudioFormat.encoding
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        stretcher?.dispose()
        stretcher = RubberBandStretcher(
            sampleRate,
            channels,
            RubberBandStretcher.OptionProcessRealTime or
                RubberBandStretcher.OptionThreadingNever or
                RubberBandStretcher.OptionChannelsTogether,
            1.0 / requestedSpeed.toDouble(),
            1.0
        ).also { it.setMaxProcessSize(8192) }
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val active = stretcher ?: return
        val bytesPerSample = if (inputEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = channels * bytesPerSample
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) return

        active.setTimeRatio(1.0 / requestedSpeed.toDouble())
        val source = inputBuffer.slice().order(ByteOrder.nativeOrder())
        val planar = Array(channels) { FloatArray(frames) }
        if (inputEncoding == C.ENCODING_PCM_FLOAT) {
            val samples = source.asFloatBuffer()
            for (frame in 0 until frames) {
                for (channel in 0 until channels) planar[channel][frame] = samples.get()
            }
        } else {
            val samples = source.asShortBuffer()
            for (frame in 0 until frames) {
                for (channel in 0 until channels) {
                    planar[channel][frame] = samples.get().toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(inputBuffer.position() + frames * frameBytes)
        active.process(planar, 0, frames, false)
        drainAvailable(active)
    }

    override fun onQueueEndOfStream() {
        val active = stretcher ?: return
        active.process(Array(channels) { FloatArray(0) }, 0, 0, true)
        drainAvailable(active)
    }

    private fun drainAvailable(active: RubberBandStretcher) {
        val available = active.available()
        if (available <= 0) return
        val planar = Array(channels) { FloatArray(available) }
        val received = active.retrieve(planar, 0, available)
        if (received <= 0) return

        val bytesPerSample = if (inputEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val output = replaceOutputBuffer(received * channels * bytesPerSample)
            .order(ByteOrder.nativeOrder())
        for (frame in 0 until received) {
            for (channel in 0 until channels) {
                val sample = planar[channel][frame]
                if (inputEncoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(sample)
                } else {
                    output.putShort((sample.coerceIn(-1f, 0.9999695f) * 32768f)
                        .roundToInt().toShort())
                }
            }
        }
        output.flip()
    }

    override fun onReset() {
        stretcher?.dispose()
        stretcher = null
        sampleRate = 0
        channels = 0
        inputEncoding = C.ENCODING_INVALID
    }
}

@UnstableApi
internal class RubberBandAudioProcessorChain : AudioProcessorChain {
    private val processor = RubberBandAudioProcessor()
    @Volatile private var speed = 1f

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(processor)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        speed = playbackParameters.speed.coerceIn(0.5f, 4f)
        processor.setPlaybackSpeed(speed)
        return PlaybackParameters(speed, 1f)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        (playoutDuration.toDouble() * speed).toLong()

    override fun getSkippedOutputFrameCount(): Long = 0L
}

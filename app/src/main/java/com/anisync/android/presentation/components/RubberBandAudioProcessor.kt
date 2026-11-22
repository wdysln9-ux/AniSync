package com.anisync.android.presentation.components

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.breakfastquay.rubberband.RubberBandStretcher
import java.nio.ByteOrder

/** Real-time Rubber Band R2 time-stretcher with pitch preserved. */
@UnstableApi
internal class RubberBandAudioProcessor : BaseAudioProcessor() {
    @Volatile private var requestedSpeed = 1f
    private var sampleRate = 0
    private var channels = 0
    private var stretcher: RubberBandStretcher? = null

    fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed.coerceIn(0.5f, 4f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
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
        val frameBytes = channels * 4
        val frames = inputBuffer.remaining() / frameBytes
        if (frames <= 0) return

        active.setTimeRatio(1.0 / requestedSpeed.toDouble())
        val source = inputBuffer.slice().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val planar = Array(channels) { FloatArray(frames) }
        for (frame in 0 until frames) {
            for (channel in 0 until channels) planar[channel][frame] = source.get()
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
        val output = replaceOutputBuffer(received * channels * 4)
            .order(ByteOrder.nativeOrder())
        for (frame in 0 until received) {
            for (channel in 0 until channels) output.putFloat(planar[channel][frame])
        }
        output.flip()
    }

    override fun onReset() {
        stretcher?.dispose()
        stretcher = null
        sampleRate = 0
        channels = 0
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

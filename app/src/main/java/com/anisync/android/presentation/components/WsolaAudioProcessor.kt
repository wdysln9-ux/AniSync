package com.anisync.android.presentation.components

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * WSOLA time-stretch processor for the CloudStream episode player.
 *
 * It uses waveform-similarity search and overlap/add, tuned around mpv scaletempo2's
 * documented defaults (20 ms overlap window, 30 ms search interval). This is an independent
 * Kotlin implementation, not a binary or source-level copy of mpv.
 *
 * Media3 supplies signed 16-bit PCM because float output is disabled for this renderer.
 */
@UnstableApi
internal class WsolaAudioProcessor : BaseAudioProcessor() {
    @Volatile private var requestedSpeed = 1f
    private var appliedSpeed = 1f
    private var sampleRate = 0
    private var channelCount = 0
    private var windowFrames = 0
    private var hopFrames = 0
    private var searchFrames = 0

    private var input = ShortArray(0)
    private var inputFrames = 0
    private var tail = FloatArray(0)
    private var hasTail = false
    private var analysisPosition = 0.0
    private var lastCandidateStart = 0

    fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed.coerceIn(0.5f, 4f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        // WSOLA's overlap window is 20 ms. The full analysis segment is two
        // overlap windows: one for the current crossfade and one retained as tail.
        windowFrames = max(512, (sampleRate * 0.040f).toInt())
        if (windowFrames % 2 != 0) windowFrames++
        hopFrames = windowFrames / 2
        // Match scaletempo2's default +/- search interval (30 ms).
        searchFrames = max(1, (sampleRate * 0.030f).toInt())
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        clearState()
        appliedSpeed = requestedSpeed
    }

    override fun onReset() {
        clearState()
        sampleRate = 0
        channelCount = 0
        windowFrames = 0
        hopFrames = 0
        searchFrames = 0
        input = ShortArray(0)
        tail = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || channelCount == 0) return

        val speed = requestedSpeed
        if (abs(speed - appliedSpeed) > 0.001f) {
            clearState()
            appliedSpeed = speed
        }

        val frameBytes = channelCount * 2
        val completeBytes = inputBuffer.remaining() - inputBuffer.remaining() % frameBytes
        if (completeBytes <= 0) return
        val frames = completeBytes / frameBytes
        ensureInputCapacity(inputFrames + frames)

        val source = inputBuffer.slice().order(inputBuffer.order())
        val startSample = inputFrames * channelCount
        repeat(frames * channelCount) { index ->
            input[startSample + index] = source.short
        }
        inputBuffer.position(inputBuffer.position() + completeBytes)
        inputFrames += frames

        if (abs(appliedSpeed - 1f) <= 0.001f) {
            emitInputAsIs()
        } else {
            processWsola(final = false)
        }
    }

    override fun onQueueEndOfStream() {
        if (inputFrames == 0) return
        if (abs(appliedSpeed - 1f) <= 0.001f || !hasTail) {
            emitInputAsIs()
            return
        }
        processWsola(final = true)
    }

    private fun clearState() {
        inputFrames = 0
        hasTail = false
        analysisPosition = 0.0
        lastCandidateStart = 0
        if (channelCount > 0 && hopFrames > 0) tail = FloatArray(hopFrames * channelCount)
    }

    private fun ensureInputCapacity(requiredFrames: Int) {
        val requiredSamples = requiredFrames * channelCount
        if (input.size >= requiredSamples) return
        var capacity = max(channelCount * windowFrames * 4, input.size)
        while (capacity < requiredSamples) capacity = max(capacity * 2, requiredSamples)
        input = input.copyOf(capacity)
    }

    private fun emitInputAsIs() {
        if (inputFrames <= 0) return
        val byteCount = inputFrames * channelCount * 2
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        for (i in 0 until inputFrames * channelCount) output.putShort(input[i])
        output.flip()
        inputFrames = 0
        hasTail = false
        analysisPosition = 0.0
        lastCandidateStart = 0
    }

    /**
     * One WSOLA step emits the overlap region and retains the candidate's second half as the
     * next overlap. Input is retained across Media3 buffers so the search window is not reset
     * at decoder-buffer boundaries.
     */
    private fun processWsola(final: Boolean) {
        if (inputFrames <= 0) return
        val outputSamples = ShortAccumulator()

        if (!hasTail) {
            if (inputFrames < windowFrames && !final) return
            if (inputFrames < windowFrames) {
                emitInputAsIs()
                return
            }
            for (frame in 0 until hopFrames) {
                for (channel in 0 until channelCount) {
                    outputSamples.add(input[frame * channelCount + channel])
                }
            }
            copySecondHalfToTail(0)
            hasTail = true
            analysisPosition = appliedSpeed.toDouble() * hopFrames
            lastCandidateStart = 0
        }

        val realInputFrames = inputFrames
        if (final) {
            val padding = searchFrames + windowFrames + hopFrames
            ensureInputCapacity(inputFrames + padding)
            input.fill(0, inputFrames * channelCount, (inputFrames + padding) * channelCount)
            inputFrames += padding
        }

        var iterations = 0
        while (iterations++ < 4096) {
            val expected = analysisPosition.toInt()
            if (final && expected >= realInputFrames) break

            val lower = max(lastCandidateStart + 1, expected - searchFrames)
            val availableMax = inputFrames - windowFrames
            val upper = if (final) min(realInputFrames - windowFrames, expected + searchFrames) else min(availableMax, expected + searchFrames)
            if (lower > upper) break

            val candidate = findBestCandidate(lower, upper)
            for (frame in 0 until hopFrames) {
                val blend = frame.toFloat() / max(1, hopFrames - 1)
                val inverse = 1f - blend
                for (channel in 0 until channelCount) {
                    val oldSample = tail[frame * channelCount + channel]
                    val newSample = input[(candidate + frame) * channelCount + channel].toFloat()
                    val mixed = oldSample * inverse + newSample * blend
                    outputSamples.add(mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
            }

            copySecondHalfToTail(candidate)
            lastCandidateStart = candidate
            analysisPosition = candidate + appliedSpeed.toDouble() * hopFrames
            if (!final) pruneOldInput()
        }

        if (final && hasTail) {
            for (sample in tail) {
                outputSamples.add(sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            inputFrames = 0
            hasTail = false
        }

        emitSamples(outputSamples.toArray())
    }

    private fun emitSamples(samples: ShortArray) {
        if (samples.isEmpty()) return
        val output = replaceOutputBuffer(samples.size * 2).order(ByteOrder.nativeOrder())
        for (sample in samples) output.putShort(sample)
        output.flip()
    }

    private fun findBestCandidate(lower: Int, upper: Int): Int {
        var bestStart = lower
        var bestScore = -Double.MAX_VALUE
        val stride = 2
        var start = lower
        while (start <= upper) {
            // Use one channel to choose a shared offset for every channel. This
            // prevents stereo phase differences from cancelling the correlation.
            var oldSum = 0.0
            var newSum = 0.0
            var dot = 0.0
            var oldSquare = 0.0
            var newSquare = 0.0
            var samples = 0
            var frame = 0
            while (frame < hopFrames) {
                val oldValue = tail[frame * channelCount].toDouble()
                val newValue = input[(start + frame) * channelCount].toDouble()
                oldSum += oldValue
                newSum += newValue
                dot += oldValue * newValue
                oldSquare += oldValue * oldValue
                newSquare += newValue * newValue
                samples++
                frame += 4
            }
            val covariance = dot - oldSum * newSum / max(1, samples)
            val oldEnergy = oldSquare - oldSum * oldSum / max(1, samples)
            val newEnergy = newSquare - newSum * newSum / max(1, samples)
            val denominator = sqrt(max(1.0, oldEnergy) * max(1.0, newEnergy))
            val score = if (oldEnergy < 1.0 || newEnergy < 1.0) {
                // During silence, waveform matching is meaningless. Keep the
                // earliest candidate instead of jumping to a noisy match.
                -abs(start - analysisPosition)
            } else {
                covariance / denominator
            }
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
            start += stride
        }
        return bestStart
    }

    private fun copySecondHalfToTail(startFrame: Int) {
        for (frame in 0 until hopFrames) {
            for (channel in 0 until channelCount) {
                tail[frame * channelCount + channel] =
                    input[(startFrame + hopFrames + frame) * channelCount + channel].toFloat()
            }
        }
    }

    private fun pruneOldInput() {
        val safeBefore = min(
            lastCandidateStart,
            max(0, floor(analysisPosition).toInt() - searchFrames)
        )
        if (safeBefore <= 0) return
        val remaining = inputFrames - safeBefore
        System.arraycopy(input, safeBefore * channelCount, input, 0, remaining * channelCount)
        inputFrames = remaining
        analysisPosition -= safeBefore
        lastCandidateStart -= safeBefore
    }

    private class ShortAccumulator {
        private var values = ShortArray(4096)
        private var size = 0

        fun add(value: Short) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }
}

/** AudioProcessorChain that replaces Media3 Sonic with the WSOLA processor for this player only. */
@UnstableApi
internal class WsolaAudioProcessorChain : AudioProcessorChain {
    private val processor = WsolaAudioProcessor()
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

package com.anisync.android.presentation.components

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Routes decoded PCM through Rubber Band's internal float processing path. */
@UnstableApi
internal class RubberBandRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            // Keep Media3 audio processing enabled; RubberBandAudioProcessor performs its own
            // float conversion and returns the original PCM encoding.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(false)
            .setAudioProcessorChain(RubberBandAudioProcessorChain())
            .build()
    }
}

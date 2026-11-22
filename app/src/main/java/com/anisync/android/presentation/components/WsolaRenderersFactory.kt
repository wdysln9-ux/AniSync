package com.anisync.android.presentation.components

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Renderer factory dedicated to CloudStream episode playback. The custom chain replaces Media3's
 * default Sonic processor so WSOLA is applied exactly once to decoded PCM.
 */
@UnstableApi
internal class WsolaRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink? {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(WsolaAudioProcessorChain())
            .build()
    }
}

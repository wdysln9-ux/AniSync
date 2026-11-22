package com.anisync.android.presentation.components

import android.content.Context

/** Audio paths exposed in the episode player's quick settings for A/B testing. */
internal enum class EpisodeAudioRenderer(val title: String) {
    MEDIA3_SONIC("Media3 Sonic"),
    WSOLA("WSOLA"),
    RUBBER_BAND("Rubber Band"),
    PLATFORM("Android / perangkat");

    companion object {
        fun fromId(value: String?): EpisodeAudioRenderer =
            entries.firstOrNull { it.name == value } ?: RUBBER_BAND
    }
}

internal object EpisodeAudioRendererPreferences {
    private const val PREFS = "episode_audio_renderer"
    private const val KEY = "selected"

    fun get(context: Context): EpisodeAudioRenderer =
        EpisodeAudioRenderer.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun set(context: Context, renderer: EpisodeAudioRenderer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, renderer.name)
            .apply()
    }
}

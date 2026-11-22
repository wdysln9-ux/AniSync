package com.anisync.android.presentation.components

import android.content.Context
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.anisync.android.data.media.MediaHttp
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * CompositionLocal providing an [ExoPlayerCache] to composables in the tree.
 *
 * The default value is `null`, meaning no caching — the [VideoPlayer] will create
 * and release its own ExoPlayer instance (original behavior).
 */
val LocalExoPlayerCache = compositionLocalOf<ExoPlayerCache?> { null }

/**
 * A scoped cache of [ExoPlayer] instances keyed by video URL.
 */
class ExoPlayerCache internal constructor(private val context: Context) {

    private val maxSize = 6

    private val players = object : LinkedHashMap<String, ExoPlayer>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExoPlayer>?): Boolean {
            val evict = size > maxSize
            if (evict) {
                eldest?.value?.release()
                Log.d(
                    "PerfMetrics",
                    "ExoPlayer evicted & released. Cache limit ($maxSize) exceeded."
                )
            }
            return evict
        }
    }

    /**
     * Returns an existing [ExoPlayer] for [url] if one is cached, or creates and
     * caches a new one.
     */
    fun getOrCreate(url: String, headers: Map<String, String> = emptyMap()): ExoPlayer {
        val cacheKey = url + headers.toSortedMap().toString()
        return players.getOrPut(cacheKey) {
            val start = System.currentTimeMillis()
            buildVideoExoPlayer(context, url, headers = headers).also {
                val initTime = System.currentTimeMillis() - start
                Log.d("PerfMetrics", "New ExoPlayer cached for $url in ${initTime}ms")
            }
        }
    }

    /** Releases all cached players and clears the cache. */
    fun releaseAll() {
        players.values.forEach { it.release() }
        players.clear()
        Log.d("PerfMetrics", "All cached ExoPlayers released.")
    }

    companion object {
        /**
         * Live caches, so the process can hand back player memory and decoders when the system
         * asks. Each cache is scoped to a composition and already releases on dispose, so this
         * only exists for the case where nothing is being disposed and memory is short anyway.
         */
        private val live: MutableSet<ExoPlayerCache> =
            Collections.newSetFromMap(ConcurrentHashMap())

        internal fun track(cache: ExoPlayerCache) {
            live += cache
        }

        internal fun untrack(cache: ExoPlayerCache) {
            live -= cache
        }

        /** Releases every player held anywhere in the process. Main thread only, as ExoPlayer requires. */
        fun releaseAllCaches() = live.forEach { it.releaseAll() }
    }
}

/**
 * Builds a prepared [ExoPlayer] for an inline video [url], muted and looping by default.
 *
 * A theme song wants the opposite of an embedded clip, so [muted], [loop] and [autoPlay]
 * are overridable: the openings and endings sheet opens with sound and plays once. A [cache]
 * keeps what was streamed on disk, so a looped or replayed song reads the file it already has.
 *
 * Single source of truth so the cached ([ExoPlayerCache]) and self-managed ([VideoPlayer])
 * paths can't drift. Two things make user-embedded clips actually play instead of erroring:
 *  - **OkHttp data source with a browser UA** ([MediaHttp.videoClient]). Hosts like catbox
 *    reject ExoPlayer's default User-Agent and answer 403, which surfaces as a "broken"
 *    video that plays fine in a browser. OkHttp also transparently follows the
 *    cross-protocol (http↔https) redirects these CDNs use — the platform
 *    `DefaultHttpDataSource` refuses those by default.
 *  - **Eager [ExoPlayer.prepare]** so buffering starts the instant the player is created
 *    (i.e. when scrolled near), not when its surface finally attaches.
 */
@OptIn(UnstableApi::class)
internal fun buildVideoExoPlayer(
    context: Context,
    url: String,
    muted: Boolean = true,
    loop: Boolean = true,
    autoPlay: Boolean = false,
    cache: Cache? = null,
    headers: Map<String, String> = emptyMap(),
    useWsolaAudio: Boolean = false,
): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            30_000,       // Start/continue with a meaningful 30-second forward buffer.
            15 * 60_000,  // Allow up to 15 minutes ahead for long-form episodes on slow networks.
            5_000,        // Avoid declaring playback ready with only a couple of seconds.
            10_000        // Rebuffer with a larger safety margin than the old 5-second setting.
        )
        // Keep several minutes behind the playhead available for backward seeking.
        .setBackBuffer(4 * 60_000, true)
        // The disk cache holds downloaded segments; prioritize time so buffering is not
        // prematurely constrained by a small sample-queue byte threshold.
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    // Episode playback uses Rubber Band's pitch-preserving time stretcher.
    // Embedded clips retain the standard Media3 renderer because they are usually muted.
    val renderersFactory = if (useWsolaAudio) {
        RubberBandRenderersFactory(context).setEnableAudioFloatOutput(true)
            .setEnableAudioOutputPlaybackParameters(false)
    } else {
        DefaultRenderersFactory(context)
            .setEnableAudioFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(false)
    }

    val diskCache = cache ?: SharedVideoDiskCache.get(context)
    return ExoPlayer.Builder(context, renderersFactory)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(videoDataSourceFactory(diskCache, headers)))
        .build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
            playWhenReady = autoPlay
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (error.isMatroskaFormatFailure() && reloadedWithoutCueSeek.add(this@apply)) {
                        Log.w("VideoPlayer", "Reloading $url without the seek for Cues", error)
                        reloadWithoutCueSeek(url, cache, headers)
                    }
                }
            })
            prepare()
        }
}

/**
 * Shared persistent cache for streamed video. The LRU evictor keeps the directory below 1.2 GB
 * and removes least-recently-used media when space is needed.
 */
@OptIn(UnstableApi::class)
internal object SharedVideoDiskCache {
    private const val MAX_CACHE_BYTES = 1_200L * 1024L * 1024L
    @Volatile private var instance: SimpleCache? = null

    fun get(context: Context): Cache = instance ?: synchronized(this) {
        instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, "stream-video-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
    }
}

@OptIn(UnstableApi::class)
private fun videoDataSourceFactory(cache: Cache?, headers: Map<String, String> = emptyMap()): DataSource.Factory {
    val http = OkHttpDataSource.Factory(MediaHttp.videoClient)
        .setUserAgent(MediaHttp.USER_AGENT)
        .setDefaultRequestProperties(headers)
    if (cache == null) return http
    return CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(http)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/** Players already moved to the fallback below, so a second failure is shown instead of looped. */
private val reloadedWithoutCueSeek: MutableSet<ExoPlayer> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

/**
 * The failure Media3 1.10 and later hit on some WebM files that keep their Cues at the end, such
 * as the clip in AniList's markdown guide (forum thread 6125). The extractor holds the track
 * formats back until it has jumped to the Cues and read them, and under the player a sample then
 * reaches its queue with no format, which fails a null check inside `SampleQueue`. Plain Media3
 * fails the same way with either HTTP stack, so this is not the data source.
 */
@OptIn(UnstableApi::class)
private fun PlaybackException.isMatroskaFormatFailure(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
        cause is Loader.UnexpectedLoaderException &&
        cause?.cause is NullPointerException

/**
 * Plays the file again without the jump to the Cues, which makes the extractor send formats as
 * soon as it reads the tracks. The file then cannot seek, but it plays and still loops from the
 * start, which is all an embedded clip needs.
 */
@OptIn(UnstableApi::class)
private fun ExoPlayer.reloadWithoutCueSeek(url: String, cache: Cache?, headers: Map<String, String> = emptyMap()) {
    val extractors = DefaultExtractorsFactory()
        .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
    setMediaSource(
        ProgressiveMediaSource.Factory(videoDataSourceFactory(cache, headers), extractors)
            .createMediaSource(MediaItem.fromUri(url))
    )
    prepare()
}

/**
 * Creates and remembers an [ExoPlayerCache] scoped to the calling composable.
 */
@Composable
fun rememberExoPlayerCache(): ExoPlayerCache {
    val context = LocalContext.current.applicationContext
    val cache = remember { ExoPlayerCache(context) }

    DisposableEffect(cache) {
        ExoPlayerCache.track(cache)
        onDispose {
            ExoPlayerCache.untrack(cache)
            cache.releaseAll()
        }
    }

    return cache
}

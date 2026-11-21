package com.anisync.android.presentation.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CloudStream-only mpv player. AnimeW uses media_kit over libmpv; this is the
 * native Android equivalent so the Compose app can use the same mpv playback
 * engine without embedding a Flutter runtime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStreamMpvPlayer(
    url: String,
    headers: Map<String, String> = emptyMap(),
    subtitles: List<Map<String, Any?>> = emptyList(),
    modifier: Modifier = Modifier,
    startMuted: Boolean = false,
    autoPlay: Boolean = true,
    onPlaybackEnded: () -> Unit = {},
    onAutoNextChange: (Boolean) -> Unit = {},
    autoEnterFullscreen: Boolean = false,
    onFullscreenEntered: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentSubtitles by rememberUpdatedState(subtitles)
    var isMuted by remember(url) { mutableStateOf(startMuted) }
    var isPlaying by remember(url) { mutableStateOf(false) }
    var isBuffering by remember(url) { mutableStateOf(true) }
    var isReady by remember(url) { mutableStateOf(false) }
    var isFullscreen by remember(url) { mutableStateOf(false) }
    var positionMs by remember(url) { mutableLongStateOf(0L) }
    var durationMs by remember(url) { mutableLongStateOf(0L) }
    var isScrubbing by remember(url) { mutableStateOf(false) }
    var errorMessage by remember(url) { mutableStateOf<String?>(null) }

    val session = remember(url, headers) {
        CloudStreamMpvSession(context.applicationContext, url, headers, autoPlay)
    }

    DisposableEffect(session) {
        session.setCallbacks(
            onReady = { isReady = true; isBuffering = false; errorMessage = null },
            onBuffering = { buffering -> isBuffering = buffering },
            onPlaying = { playing -> isPlaying = playing },
            onPosition = { positionMs = it },
            onDuration = { durationMs = it },
            onEnded = { currentOnPlaybackEnded() },
            onError = { errorMessage = it; isReady = false; isBuffering = false },
            subtitles = { currentSubtitles },
        )
        session.initialize()
        onDispose { session.release() }
    }

    LaunchedEffect(session, isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            session.refreshPosition()
            delay(250)
        }
    }

    LaunchedEffect(isMuted) { session.setMuted(isMuted) }

    LaunchedEffect(autoEnterFullscreen, isReady, isFullscreen) {
        if (autoEnterFullscreen && isReady && !isFullscreen) {
            isFullscreen = true
            onFullscreenEntered()
        }
    }

    val displayAspect = 16f / 9f
    val playerState = when {
        errorMessage != null -> PlayerState.Error
        !isReady -> PlayerState.Loading
        isBuffering -> PlayerState.Buffering
        else -> PlayerState.Ready
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
    ) {
        if (!isFullscreen) {
            MpvSurface(session, Modifier.fillMaxSize())
            PlayerStatusVisuals(
                isLoading = playerState == PlayerState.Loading,
                isBuffering = playerState == PlayerState.Buffering,
                errorMessage = errorMessage,
                showShimmer = true,
                onRetry = { errorMessage = null; isReady = false; isBuffering = true; session.retry() }
            )
            if (playerState == PlayerState.Ready || playerState == PlayerState.Buffering) {
                VideoControlsOverlay(
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    isMuted = isMuted,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isFullscreen = false,
                    onPlayPause = { session.setPaused(isPlaying) },
                    onToggleMute = { isMuted = !isMuted },
                    onSeek = { fraction ->
                        isScrubbing = true
                        if (durationMs > 0) {
                            positionMs = (fraction * durationMs).toLong()
                            session.seekTo(positionMs)
                        }
                    },
                    onSeekBy = { delta ->
                        val target = (positionMs + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                        positionMs = target
                        session.seekTo(target)
                    },
                    onSeekFinished = { isScrubbing = false },
                    onSetPlaybackSpeed = session::setSpeed,
                    sourceOptions = emptyList(),
                    onSelectSource = {},
                    onAutoNextChange = onAutoNextChange,
                    onToggleFullscreen = { isFullscreen = true },
                    onBack = null,
                )
            }
        }
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            )
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MpvSurface(session, Modifier.fillMaxSize())
                PlayerStatusVisuals(
                    isLoading = playerState == PlayerState.Loading,
                    isBuffering = playerState == PlayerState.Buffering,
                    errorMessage = errorMessage,
                    showShimmer = false,
                    onRetry = { errorMessage = null; isReady = false; isBuffering = true; session.retry() }
                )
                if (playerState == PlayerState.Ready || playerState == PlayerState.Buffering) {
                    VideoControlsOverlay(
                        modifier = Modifier.fillMaxSize(),
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        isMuted = isMuted,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isFullscreen = true,
                        onPlayPause = { session.setPaused(isPlaying) },
                        onToggleMute = { isMuted = !isMuted },
                        onSeek = { fraction ->
                            isScrubbing = true
                            if (durationMs > 0) {
                                positionMs = (fraction * durationMs).toLong()
                                session.seekTo(positionMs)
                            }
                        },
                        onSeekBy = { delta ->
                            val target = (positionMs + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                            positionMs = target
                            session.seekTo(target)
                        },
                        onSeekFinished = { isScrubbing = false },
                        onSetPlaybackSpeed = session::setSpeed,
                        sourceOptions = emptyList(),
                        onSelectSource = {},
                        onAutoNextChange = onAutoNextChange,
                        onToggleFullscreen = { isFullscreen = false },
                        onBack = { isFullscreen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun MpvSurface(session: CloudStreamMpvSession, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        session.attachSurface(holder.surface)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        session.detachSurface()
                    }
                })
            }
        },
        update = {},
    )
}

private class CloudStreamMpvSession(
    context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val autoPlay: Boolean,
) {
    private val mpv = requireNotNull(MPVLib.create(context)) { "Gagal membuat mesin mpv." }
    private val main = Handler(Looper.getMainLooper())
    private val released = AtomicBoolean(false)
    private var loaded = false
    private var onReady: () -> Unit = {}
    private var onBuffering: (Boolean) -> Unit = {}
    private var onPlaying: (Boolean) -> Unit = {}
    private var onPosition: (Long) -> Unit = {}
    private var onDuration: (Long) -> Unit = {}
    private var onEnded: () -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var subtitleProvider: () -> List<Map<String, Any?>> = { emptyList() }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Double) {
            when (property) {
                "time-pos" -> main.post { onPosition((value * 1000.0).toLong().coerceAtLeast(0)) }
                "duration" -> main.post { onDuration((value * 1000.0).toLong().coerceAtLeast(0)) }
            }
        }
        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> main.post { onPlaying(!value) }
                "paused-for-cache" -> main.post { onBuffering(value) }
            }
        }
        override fun eventProperty(property: String, value: String) = Unit
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> main.post { onReady() }
            }
        }
        override fun endFile(reason: Int, error: Int) {
            when (reason) {
                MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_EOF -> main.post { onEnded() }
                MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR ->
                    main.post { onError("Video gagal diputar (mpv: $error).") }
            }
        }
    }

    fun setCallbacks(
        onReady: () -> Unit,
        onBuffering: (Boolean) -> Unit,
        onPlaying: (Boolean) -> Unit,
        onPosition: (Long) -> Unit,
        onDuration: (Long) -> Unit,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
        subtitles: () -> List<Map<String, Any?>>,
    ) {
        this.onReady = onReady
        this.onBuffering = onBuffering
        this.onPlaying = onPlaying
        this.onPosition = onPosition
        this.onDuration = onDuration
        this.onEnded = onEnded
        this.onError = onError
        this.subtitleProvider = subtitles
    }

    fun initialize() {
        if (released.get() || loaded) return
        mpv.addObserver(observer)
        mpv.requestLogMessages("warn")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("hwdec", "auto-safe")
        mpv.setOptionString("audio-pitch-correction", "yes")
        mpv.setOptionString("force-window", "yes")
        mpv.setOptionString("terminal", "no")
        mpv.setOptionString("sub-auto", "fuzzy")
        mpv.setOptionString("keep-open", "no")
        if (headers.isNotEmpty()) {
            val fields = headers.entries.joinToString(",") { (key, value) -> "$key: $value" }
            mpv.setOptionString("http-header-fields", fields)
        }
        mpv.init()
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    fun attachSurface(surface: Surface) {
        if (released.get() || !surface.isValid) return
        try {
            mpv.attachSurface(surface)
            if (!loaded) {
                loaded = true
                mpv.command(arrayOf("loadfile", url, "replace"))
                if (!autoPlay) mpv.setPropertyBoolean("pause", true)
                addSubtitles()
            }
        } catch (t: Throwable) {
            main.post { onError(t.message ?: "Gagal membuka video.") }
        }
    }

    private fun addSubtitles() {
        subtitleProvider().forEachIndexed { index, subtitle ->
            val subUrl = subtitle["url"]?.toString()?.takeIf { it.startsWith("http") } ?: return@forEachIndexed
            val language = subtitle["lang"]?.toString()?.takeIf(String::isNotBlank) ?: "Subtitle"
            val flags = if (index == 0) "select" else "auto"
            runCatching { mpv.command(arrayOf("sub-add", subUrl, flags, language, language)) }
        }
    }

    fun detachSurface() {
        if (!released.get()) runCatching { mpv.detachSurface() }
    }

    fun setPaused(paused: Boolean) {
        if (!released.get()) runCatching { mpv.setPropertyBoolean("pause", paused) }
    }

    fun setMuted(muted: Boolean) {
        if (!released.get()) runCatching { mpv.setPropertyBoolean("mute", muted) }
    }

    fun setSpeed(speed: Float) {
        if (!released.get()) runCatching { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun seekTo(positionMs: Long) {
        if (!released.get()) runCatching { mpv.setPropertyDouble("time-pos", positionMs.coerceAtLeast(0L) / 1000.0) }
    }

    fun refreshPosition() {
        if (released.get()) return
        val position = mpv.getPropertyDouble("time-pos")
        val duration = mpv.getPropertyDouble("duration")
        if (position != null) main.post { onPosition((position * 1000.0).toLong().coerceAtLeast(0)) }
        if (duration != null) main.post { onDuration((duration * 1000.0).toLong().coerceAtLeast(0)) }
    }

    fun retry() {
        if (released.get()) return
        loaded = false
        runCatching { mpv.command(arrayOf("loadfile", url, "replace")) }
            .onFailure { main.post { onError(it.message ?: "Gagal mengulang video.") } }
        loaded = true
        addSubtitles()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { mpv.removeObserver(observer) }
        runCatching { mpv.detachSurface() }
        runCatching { mpv.command(arrayOf("stop")) }
        mpv.destroy()
    }
}

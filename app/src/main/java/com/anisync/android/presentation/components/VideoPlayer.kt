package com.anisync.android.presentation.components

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.anisync.android.R
import com.anisync.android.presentation.util.shimmerEffect
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/** Visual state of the video player. Shared with [FullscreenPlayerActivity]. */
internal enum class PlayerState { Loading, Ready, Buffering, Error }

/**
 * Tallest aspect ratio (width/height) the inline frame will take. A portrait clip taller than
 * this (e.g. a 9:16 phone capture) is pillar-boxed inside a 4:5 frame instead of growing to
 * ~1.8× the screen width and shoving the rest of the post off-screen.
 */
private const val MIN_DISPLAY_ASPECT = 0.8f // 4:5

/** Widest aspect ratio the inline frame will take, so an ultrawide clip keeps a usable height. */
private const val MAX_DISPLAY_ASPECT = 2.4f // ~21:9

internal const val DEFAULT_VIDEO_ASPECT = 16f / 9f

/** How long the controls linger after the last interaction before fading, while playing. */
private const val CONTROLS_HIDE_DELAY_MS = 3000L

/** Surface width past which the controls step up to tablet-sized touch targets. */
private val WIDE_CONTROLS_MIN_WIDTH = 600.dp

/**
 * An inline video player for the short, looping, user-embedded clips that appear in rich text
 * (activity posts, forum replies, bios). Built on Media3 [ExoPlayer] + [ContentFrame] with a fully
 * custom Material 3 overlay.
 *
 * UX:
 * - **Adaptive framing** — the frame's aspect ratio is clamped to a sane range and the video is
 *   letterboxed ([ContentScale.Fit]) inside themed bars, so odd-shaped, tiny, or huge clips all sit
 *   tidily in the feed instead of distorting or dominating the screen.
 * - **Auto-hiding controls** — controls appear on tap and fade [CONTROLS_HIDE_DELAY_MS] after the
 *   last interaction while playing; they stay put while paused. A single tap toggles them; the
 *   centre button (not the whole surface) controls playback, so there are no accidental pauses.
 * - **Real scrubber** — current/total time with drag-to-seek.
 * - **Fullscreen** — hands the *same* player to a dedicated [FullscreenPlayerActivity] (own
 *   lifecycle + orientation), which auto-rotates to landscape for wide clips. Using a separate
 *   activity rather than an in-list dialog is what makes rotation robust: a dialog hosted inside a
 *   `LazyColumn` item is torn down the moment a rotation relayouts (and disposes) that item.
 * - **Robust states** — loading skeleton, mid-playback buffering spinner, and a friendly retry card.
 *
 * @param url The URL of the video to play.
 * @param modifier Optional [Modifier] for the root container.
 * @param playerCache [ExoPlayerCache] for retaining player state across recomposition / scrolling.
 * Defaults to [LocalExoPlayerCache]. When `null`, the player is self-managed and released on dispose.
 * @param artwork Drawn in place of the picture, for a source that has none such as an audio file.
 */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@ExperimentalMaterial3Api
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    playerCache: ExoPlayerCache? = LocalExoPlayerCache.current,
    startMuted: Boolean = true,
    loop: Boolean = true,
    autoPlay: Boolean = false,
    useWsolaAudio: Boolean = false,
    artwork: (@Composable () -> Unit)? = null,
    sourceOptions: List<String> = emptyList(),
    onSelectSource: (Int) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onAutoNextChange: (Boolean) -> Unit = {},
    autoEnterFullscreen: Boolean = false,
    onFullscreenEntered: () -> Unit = {},
    onCinematicLightingChange: (Float) -> Unit = {},
    preloadUrl: String? = null,
    preloadHeaders: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isMuted by remember { mutableStateOf(startMuted) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var videoAspectRatio by remember { mutableFloatStateOf(DEFAULT_VIDEO_ASPECT) }
    var playerState by remember { mutableStateOf(PlayerState.Loading) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // True while the player has been handed to the fullscreen activity; the inline surface detaches
    // and the lifecycle observer stops pausing, so the same instance keeps playing over there.
    var handedOff by remember { mutableStateOf(false) }
    val isHandedOff by rememberUpdatedState(handedOff)
    var cinematicOverlayIntensity by remember { mutableFloatStateOf(if (PlayerPreferences.cinematicEnabled(context)) PlayerPreferences.cinematicIntensity(context) else 0f) }
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)

    // Cached player survives scrolling; self-managed one is built+released here. Both go through
    // buildVideoExoPlayer so the OkHttp/browser-UA data source and eager prepare apply identically.
    // The self-managed instance uses the application context so it can be safely handed to the
    // fullscreen activity without leaking this composition's context.
    val exoPlayer = if (playerCache != null && !useWsolaAudio) {
        remember(url, headers) { playerCache.getOrCreate(url, headers) }
    } else {
        remember(url, headers, useWsolaAudio) {
            buildVideoExoPlayer(
                context.applicationContext,
                url,
                startMuted,
                loop,
                autoPlay,
                headers = headers,
                useWsolaAudio = useWsolaAudio
            )
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !isHandedOff) currentOnPlaybackEnded()
                if (playbackState == Player.STATE_READY) {
                    val d = exoPlayer.duration
                    if (d > 0) durationMs = d
                }
                playerState = mapPlaybackState(playbackState, playerState)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Already being retried by the player itself, see buildVideoExoPlayer.
                if (exoPlayer.playbackState != Player.STATE_IDLE) return
                android.util.Log.e(
                    "VideoPlayer",
                    "Playback error for URL: $url, code: ${error.errorCode}, message: ${error.message}",
                    error
                )
                playerState = PlayerState.Error
                errorMessage = playbackErrorMessage(context, error)
            }
        }

        // Sync initial state from an already-prepared cached player.
        if (exoPlayer.playbackState == Player.STATE_READY) {
            playerState = PlayerState.Ready
            if (exoPlayer.duration > 0) durationMs = exoPlayer.duration
            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
        }
        if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
            videoAspectRatio = exoPlayer.videoSize.width.toFloat() / exoPlayer.videoSize.height.toFloat()
        }
        isPlaying = exoPlayer.isPlaying
        exoPlayer.setPlaybackParameters(PlaybackParameters(PlayerPreferences.playbackSpeed(context), 1f))
        exoPlayer.volume = if (PlayerPreferences.isMuted(context, startMuted)) 0f else 1f
        isMuted = exoPlayer.volume == 0f
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            // Only release if self-managed; cached players are released by ExoPlayerCache.releaseAll().
            if (playerCache == null || useWsolaAudio) exoPlayer.release() else exoPlayer.pause()
        }
    }

    // Prepare the next episode only after the current stream has reached its forward-buffer
    // target (or the end of a shorter episode). The second player shares the same disk cache,
    // stays paused, and is released whenever the next URL changes or this player leaves.
    LaunchedEffect(exoPlayer, preloadUrl, preloadHeaders) {
        val nextUrl = preloadUrl?.takeIf { it.isNotBlank() && it != url } ?: return@LaunchedEffect
        var nextPlayer: ExoPlayer? = null
        try {
            while (true) {
                val total = exoPlayer.duration
                val ahead = exoPlayer.bufferedPosition - exoPlayer.currentPosition
                val targetReached = total > 0L &&
                    (exoPlayer.bufferedPosition >= total - 2_000L || ahead >= 15L * 60_000L)
                if (targetReached) break
                delay(1_000)
            }
            nextPlayer = buildVideoExoPlayer(
                context.applicationContext,
                nextUrl,
                muted = true,
                loop = false,
                autoPlay = false,
                headers = preloadHeaders,
                useWsolaAudio = useWsolaAudio
            )
            // Keep it prepared and paused so its downloaded segments enter the shared disk cache.
            awaitCancellation()
        } finally {
            nextPlayer?.release()
        }
    }

    // Drive the scrubber while playing (paused when the user is dragging it).
    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            positionMs = exoPlayer.currentPosition
            bufferedPositionMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            val d = exoPlayer.duration
            if (d > 0) durationMs = d
            delay(250)
        }
    }

    // Pause on background, resume on foreground (only if it was playing). Skipped while handed off
    // to fullscreen — that activity owns playback then, and pausing here would stop it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (!handedOff) exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (isPlaying && !handedOff) exoPlayer.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // When the fullscreen activity returns, reclaim the surface.
    val fullscreenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { handedOff = false }

    DisposableEffect(exoPlayer) {
        FullscreenVideoBridge.onPlaybackEnded = {
            currentOnPlaybackEnded()
            FullscreenVideoBridge.closeFullscreen?.invoke()
        }
        onDispose {
            if (FullscreenVideoBridge.onPlaybackEnded != null) FullscreenVideoBridge.onPlaybackEnded = null
        }
    }

    val onPlayPause: () -> Unit = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }
    val onToggleMute: () -> Unit = {
        isMuted = !isMuted
        PlayerPreferences.setMuted(context, isMuted)
        exoPlayer.volume = if (isMuted) 0f else 1f
    }
    val onSeek: (Float) -> Unit = { fraction ->
        isScrubbing = true
        val d = durationMs
        if (d > 0) {
            positionMs = (fraction * d).toLong()
            exoPlayer.seekTo(positionMs)
        }
    }
    val onSeekFinished: () -> Unit = { isScrubbing = false }
    val onRetry: () -> Unit = {
        playerState = PlayerState.Loading
        errorMessage = null
        // The failed source stays set, so preparing again retries it with whatever extractor
        // settings the player last chose for this file.
        exoPlayer.prepare()
    }
    val onEnterFullscreen: () -> Unit = {
        FullscreenVideoBridge.player = exoPlayer
        handedOff = true
        onFullscreenEntered()
        fullscreenLauncher.launch(
            Intent(context, FullscreenPlayerActivity::class.java)
                .putExtra(FullscreenPlayerActivity.EXTRA_ASPECT, videoAspectRatio)
        )
    }

    LaunchedEffect(playerState, autoEnterFullscreen, handedOff) {
        if (autoEnterFullscreen && playerState == PlayerState.Ready && !handedOff) onEnterFullscreen()
    }

    // Keep the source's real aspect ratio: the frame always takes the full available width,
    // then grows vertically for portrait video instead of cropping it into a fixed 4:5 box.
    val displayAspect = videoAspectRatio.coerceAtLeast(0.1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(displayAspect)
            .clip(RoundedCornerShape(24.dp))
            // Black, not a surface tint: any letterboxing a clip's aspect leaves should read as
            // part of the picture rather than a pale frame around it.
            .background(Color.Black)
    ) {
        if (playerState != PlayerState.Error) {
            PlayerSurface(
                exoPlayer = exoPlayer,
                active = !handedOff,
                modifier = Modifier.fillMaxSize()
            )
            if (artwork != null) {
                Box(Modifier.fillMaxSize()) { artwork() }
            }
            if (cinematicOverlayIntensity > 0f) {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFFFFD6A0).copy(alpha = cinematicOverlayIntensity * 0.22f), Color.Transparent))))
            }
        }

        PlayerStatusVisuals(
            isLoading = playerState == PlayerState.Loading,
            isBuffering = playerState == PlayerState.Buffering,
            errorMessage = if (playerState == PlayerState.Error) errorMessage else null,
            showShimmer = true,
            onRetry = onRetry
        )

        if (playerState == PlayerState.Ready || playerState == PlayerState.Buffering) {
            VideoControlsOverlay(
                isPlaying = isPlaying,
                isBuffering = playerState == PlayerState.Buffering,
                isMuted = isMuted,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                isFullscreen = false,
                onPlayPause = onPlayPause,
                onToggleMute = onToggleMute,
                onSeek = onSeek,
                onSeekBy = { delta ->
                    val target = (exoPlayer.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                    exoPlayer.seekTo(target)
                    positionMs = target
                },
                onSeekFinished = onSeekFinished,
                onSetPlaybackSpeed = { exoPlayer.setPlaybackParameters(PlaybackParameters(it, 1f)) },
                sourceOptions = sourceOptions,
                onSelectSource = onSelectSource,
                onAutoNextChange = onAutoNextChange,
                onCinematicLightingChange = { intensity ->
                    cinematicOverlayIntensity = intensity
                    FullscreenVideoBridge.cinematicIntensity.floatValue = intensity
                    onCinematicLightingChange(intensity)
                },
                onToggleFullscreen = onEnterFullscreen,
                onBack = null
            )
        }
    }
}

/** Maps an ExoPlayer playback-state int to a [PlayerState], preserving the buffering/loading nuance. */
internal fun mapPlaybackState(playbackState: Int, current: PlayerState): PlayerState = when (playbackState) {
    Player.STATE_READY -> PlayerState.Ready
    Player.STATE_BUFFERING ->
        if (current == PlayerState.Ready || current == PlayerState.Buffering) PlayerState.Buffering
        else PlayerState.Loading
    Player.STATE_ENDED -> PlayerState.Ready
    Player.STATE_IDLE -> current
    else -> current
}

/** Friendly, user-facing copy for a playback failure. */
internal fun playbackErrorMessage(context: Context, error: PlaybackException): String {
    val status = error.httpStatusCode()
    val messageRes = when {
        status == HTTP_TOO_MANY_REQUESTS -> R.string.player_error_rate_limited
        status != null && status >= 500 -> R.string.player_error_server
        status == HTTP_NOT_FOUND || status == HTTP_GONE -> R.string.player_error_missing

        else -> when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                R.string.player_error_network

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> R.string.player_error_missing

            // The host answered with something that is not video, which in practice is an
            // error page from a struggling CDN rather than a dead file.
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> R.string.player_error_server

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> R.string.player_error_format

            else -> R.string.player_error_generic
        }
    }
    return context.getString(messageRes)
}

/**
 * The HTTP status behind a playback failure, when there was one.
 *
 * Media3 reports a 503 and a 404 under codes that read alike, so the status is what separates
 * "the host is down, wait" from "this file is gone, do not bother waiting". It sits on the
 * cause chain rather than the exception itself.
 */
private fun PlaybackException.httpStatusCode(): Int? {
    var cause: Throwable? = this
    while (cause != null) {
        if (cause is InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410

/**
 * The native video surface. [active] hands the shared [exoPlayer] to exactly one surface at a time
 * (inline vs. fullscreen activity), so the two never fight over it. [ContentScale.Fit] letterboxes
 * the real video inside whatever frame it's given. A TextureView rather than a SurfaceView, so the
 * video respects Compose clipping and z-order in animated and bottom-sheet containers.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSurface(
    exoPlayer: ExoPlayer,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    LaunchedEffect(exoPlayer, active) {
        if (active && exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
    }
    ContentFrame(
        player = if (active) exoPlayer else null,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * Non-interactive (except retry) status layer: loading skeleton, mid-playback buffering spinner, and
 * error card. Shared by the inline and fullscreen presentations.
 */
@kotlin.OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerStatusVisuals(
    isLoading: Boolean,
    isBuffering: Boolean,
    errorMessage: String?,
    showShimmer: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (showShimmer) Modifier.shimmerEffect() else Modifier.background(Color.Black)),
                contentAlignment = Alignment.Center
            ) {
                ContainedLoadingIndicator(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
            }
        }

        AnimatedVisibility(visible = isBuffering, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), modifier = Modifier.size(56.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        LoadingIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        AnimatedVisibility(visible = errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                // The player is locked to the clip's aspect ratio, so this column has to fit a
                // box it does not control. It scrolls rather than clipping, which is what used
                // to cut the retry button in half once a message ran to two lines.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.BrokenImage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = errorMessage ?: stringResource(R.string.player_error_generic),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(10.dp))

                    FilledTonalButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(100),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.retry),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/**
 * The auto-hiding control surface drawn over the video. Owns its own visibility so the inline and
 * fullscreen instances hide independently. A single tap on empty space toggles the controls; the
 * centre button — not the whole surface — toggles playback, which avoids accidental pauses.
 *
 * @param onBack When non-null, a back affordance is shown (used by fullscreen to close).
 */
@Composable
internal fun VideoControlsOverlay(
    isPlaying: Boolean,
    isBuffering: Boolean,
    isMuted: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedPositionMs: Long = 0L,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    sourceOptions: List<String>,
    onSelectSource: (Int) -> Unit,
    onAutoNextChange: (Boolean) -> Unit = {},
    onCinematicLightingChange: (Float) -> Unit = {},
    onResetStretch: () -> Unit = {},
    displayMode: String = "Fit",
    onDisplayModeChange: (String) -> Unit = {},
    onToggleFullscreen: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on every interaction to restart the auto-hide countdown.
    var interactions by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var quickSettingsExpanded by remember { mutableStateOf(false) }
    var playerSettingsExpanded by remember { mutableStateOf(false) }
    var selectedSpeed by remember { mutableFloatStateOf(PlayerPreferences.playbackSpeed(context)) }
    var autoNextEnabled by remember { mutableStateOf(PlayerPreferences.autoNext(context)) }
    var cinematicEnabled by remember { mutableStateOf(PlayerPreferences.cinematicEnabled(context)) }
    var cinematicSettingsExpanded by remember { mutableStateOf(false) }
    var cinematicIntensity by remember { mutableFloatStateOf(PlayerPreferences.cinematicIntensity(context)) }

    // Push the saved preference to the host player so auto-next behaves consistently after reopening.
    LaunchedEffect(Unit) { onAutoNextChange(autoNextEnabled) }

    // Auto-hide while playing; idle countdown restarts whenever an interaction bumps [interactions].
    LaunchedEffect(controlsVisible, isPlaying, isBuffering, interactions) {
        if (controlsVisible && isPlaying && !isBuffering) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // Always reveal controls when playback pauses or stalls.
    LaunchedEffect(isPlaying, isBuffering) {
        if (!isPlaying || isBuffering) controlsVisible = true
    }

    val poke: () -> Unit = { interactions++ }
    LaunchedEffect(seekFeedback) { if (seekFeedback != null) { delay(650); seekFeedback = null } }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Controls are laid out against the surface they sit on, not the device: a tablet's
        // fullscreen video is two to three times the width of a phone's, and phone-sized touch
        // targets look stranded on it.
        val wide = maxWidth >= WIDE_CONTROLS_MIN_WIDTH
        val buttonSize = if (wide) 56.dp else 40.dp
        val buttonIconSize = if (wide) 28.dp else 20.dp
        val playSize = when {
            wide -> 96.dp
            isFullscreen -> 72.dp
            else -> 64.dp
        }
        val playIconSize = when {
            wide -> 48.dp
            isFullscreen -> 36.dp
            else -> 30.dp
        }
        val edgePadding = if (wide) 20.dp else 12.dp
        val timeStyle = if (wide) MaterialTheme.typography.titleSmall
        else MaterialTheme.typography.labelSmall

        // Tap layer: toggles control visibility (sits below the controls, which consume their own taps).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible }, onDoubleTap = { offset -> controlsVisible = true; poke(); val forward = offset.x >= size.width / 2f; onSeekBy(if (forward) 10_000L else -10_000L); seekFeedback = if (forward) "+10 detik" else "-10 detik" })
                }
        )

        AnimatedVisibility(visible = seekFeedback != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White) {
                Text(text = seekFeedback.orEmpty(), modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f)
                            )
                        )
                    )
            ) {
                // Back (fullscreen only).
                if (onBack != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.5f),
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(edgePadding)
                            .size(buttonSize)
                    ) {
                        IconButton(onClick = { poke(); onBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(buttonIconSize)
                            )
                        }
                    }
                }

                // Quick Settings (top-end), replacing the old mute shortcut.
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(edgePadding)
                        .size(buttonSize)
                ) {
                    IconButton(onClick = { poke(); quickSettingsExpanded = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan pemutar", modifier = Modifier.size(buttonIconSize))
                    }
                }
                if (playerSettingsExpanded) {
                    AlertDialog(
                        onDismissRequest = { playerSettingsExpanded = false },
                        title = { Text("Pengaturan pemutar") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Mode tampilan")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Fit", "Isi layar", "Stretch").forEach { mode ->
                                        TextButton(onClick = { onDisplayModeChange(mode); poke() }) {
                                            Text(if (displayMode == mode) "✓ $mode" else mode)
                                        }
                                    }
                                }
                                Text("Kecepatan pemutaran · ${selectedSpeed}x")
                                Slider(
                                    value = selectedSpeed,
                                    onValueChange = { selectedSpeed = it; PlayerPreferences.setPlaybackSpeed(context, it); onSetPlaybackSpeed(it) },
                                    valueRange = 0.5f..4f,
                                    steps = 13
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Putar episode berikutnya otomatis")
                                    androidx.compose.material3.Switch(
                                        checked = autoNextEnabled,
                                        onCheckedChange = { autoNextEnabled = it; PlayerPreferences.setAutoNext(context, it); onAutoNextChange(it); poke() }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(if (isMuted) "Suara mati" else "Suara aktif")
                                    androidx.compose.material3.Switch(
                                        checked = !isMuted,
                                        onCheckedChange = { onToggleMute(); poke() }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Pencahayaan sinematik")
                                    androidx.compose.material3.Switch(
                                        checked = cinematicEnabled,
                                        onCheckedChange = { enabled ->
                                            cinematicEnabled = enabled
                                            PlayerPreferences.setCinematicEnabled(context, enabled)
                                            onCinematicLightingChange(if (enabled) cinematicIntensity else 0f)
                                        }
                                    )
                                }
                                Text("Intensitas cahaya · ${(cinematicIntensity * 100).toInt()}%")
                                Slider(
                                    value = cinematicIntensity,
                                    onValueChange = { cinematicIntensity = it; PlayerPreferences.setCinematicIntensity(context, it); if (cinematicEnabled) onCinematicLightingChange(it) },
                                    valueRange = 0.05f..0.8f,
                                    enabled = cinematicEnabled
                                )
                                TextButton(onClick = { onResetStretch(); poke() }) { Text("Reset Stretch") }
                                Text(
                                    "Cubit layar dengan dua jari untuk mengatur lebar video. Garis rasio dan snap akan muncul selama pengaturan.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { playerSettingsExpanded = false }) { Text("Selesai") }
                        }
                    )
                }

                if (cinematicSettingsExpanded) {
                    AlertDialog(
                        onDismissRequest = { cinematicSettingsExpanded = false },
                        title = { Text("Pencahayaan sinematik") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("Aktifkan pencahayaan")
                                    androidx.compose.material3.Switch(checked = cinematicEnabled, onCheckedChange = { enabled -> cinematicEnabled = enabled; PlayerPreferences.setCinematicEnabled(context, enabled); onCinematicLightingChange(if (enabled) cinematicIntensity else 0f) })
                                }
                                Text("Intensitas cahaya")
                                Slider(value = cinematicIntensity, onValueChange = { cinematicIntensity = it; PlayerPreferences.setCinematicIntensity(context, it); if (cinematicEnabled) onCinematicLightingChange(it) }, valueRange = 0.05f..0.8f, enabled = cinematicEnabled)
                                Text("Atur kekuatan efek pencahayaan di sekitar pemutar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        confirmButton = { TextButton(onClick = { cinematicSettingsExpanded = false }) { Text("Selesai") } }
                    )
                }

                DropdownMenu(expanded = quickSettingsExpanded, onDismissRequest = { quickSettingsExpanded = false }) {
                    DropdownMenuItem(text = { Text("Pengaturan lengkap") }, onClick = { quickSettingsExpanded = false; playerSettingsExpanded = true; poke() })
                    DropdownMenuItem(text = { Text("Kecepatan") }, onClick = {})
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { speed ->
                        DropdownMenuItem(text = { Text("${if (selectedSpeed == speed) "✓ " else ""}${speed}x") }, onClick = { selectedSpeed = speed; PlayerPreferences.setPlaybackSpeed(context, speed); onSetPlaybackSpeed(speed); poke() })
                    }
                    if (sourceOptions.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Ganti sumber") }, onClick = {})
                        sourceOptions.forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { onSelectSource(index); quickSettingsExpanded = false; poke() })
                        }
                    }
                    DropdownMenuItem(text = { Text("${if (autoNextEnabled) "✓ " else ""}Putar episode berikutnya otomatis") }, onClick = { autoNextEnabled = !autoNextEnabled; PlayerPreferences.setAutoNext(context, autoNextEnabled); onAutoNextChange(autoNextEnabled); poke() })
                    DropdownMenuItem(text = { Text("Pencahayaan sinematik · Pengaturan") }, onClick = { quickSettingsExpanded = false; cinematicSettingsExpanded = true; poke() })
                    DropdownMenuItem(text = { Text("${if (isMuted) "Aktifkan suara" else "Bisukan"}") }, onClick = { onToggleMute(); poke() })
                }

                // Centre play / pause (hidden while buffering — the spinner shows instead).
                if (!isBuffering) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(playSize)
                    ) {
                        IconButton(onClick = { poke(); onPlayPause() }, modifier = Modifier.fillMaxSize()) {
                            Crossfade(targetState = isPlaying, label = "playpause_crossfade") { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playing) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                                    modifier = Modifier.size(playIconSize)
                                )
                            }
                        }
                    }
                }

                // Bottom bar: elapsed · scrubber · duration · fullscreen toggle.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                                                .padding(start = edgePadding, end = edgePadding / 3, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(positionMs),
                        style = timeStyle,
                        color = Color.White
                    )

                    val seekState = remember { SliderState(fraction) }
                    seekState.value = fraction
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (wide) 12.dp else 8.dp)
                            .height(if (wide) 40.dp else 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Keep the stock Material slider untouched; draw only the extra buffered
                        // segment over its inactive track, without replacing its thumb or colors.
                        Slider(
                            state = seekState,
                            onValueChange = { poke(); onSeek(it) },
                            onValueChangeFinished = onSeekFinished,
                            modifier = Modifier.fillMaxSize()
                        )
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        ) {
                            if (bufferedFraction > fraction) {
                                val startX = (size.width * fraction + 7.dp.toPx()).coerceAtMost(size.width)
                                val endX = size.width * bufferedFraction
                                if (endX > startX) {
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.55f),
                                        start = androidx.compose.ui.geometry.Offset(startX, size.height / 2f),
                                        end = androidx.compose.ui.geometry.Offset(endX, size.height / 2f),
                                        strokeWidth = 4.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = formatTime(durationMs),
                        style = timeStyle,
                        color = Color.White
                    )

                    Spacer(Modifier.width(4.dp))

                    IconButton(onClick = { poke(); onToggleFullscreen() }) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isFullscreen) stringResource(R.string.cd_exit_fullscreen) else stringResource(R.string.cd_fullscreen),
                            tint = Color.White,
                            modifier = Modifier.size(if (wide) 28.dp else 22.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Formats a millisecond position as `m:ss` (or `0:00` when unknown). */
internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

package com.anisync.android.presentation.components

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.anisync.android.ui.theme.AppTheme
import kotlinx.coroutines.delay

/**
 * Process-wide handoff slot for the [ExoPlayer] the inline [VideoPlayer] is sharing with the
 * fullscreen activity. A single reference (not a parcelled URL) so the exact same instance — with
 * its buffered data and current position — is what plays in fullscreen: no re-fetch, no double audio.
 */
@OptIn(UnstableApi::class)
/**
 * Device-wide player preferences. Shared by inline and fullscreen playback, and retained across
 * activities, episodes and app launches.
 */
internal object PlayerPreferences {
    private const val PREFS = "anisync_player_settings"
    private const val SPEED = "playback_speed"
    private const val MUTED = "muted"
    private const val DISPLAY_MODE = "display_mode"
    private const val AUTO_NEXT = "auto_next"
    private const val CINEMATIC_ENABLED = "cinematic_enabled"
    private const val CINEMATIC_INTENSITY = "cinematic_intensity"
    private const val STRETCH_SCALE = "stretch_scale"

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun isMuted(context: android.content.Context, defaultValue: Boolean = true) = prefs(context).getBoolean(MUTED, defaultValue)
    fun setMuted(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(MUTED, value).apply()
    }
    fun playbackSpeed(context: android.content.Context) = prefs(context).getFloat(SPEED, 1f).coerceIn(0.5f, 4f)
    fun setPlaybackSpeed(context: android.content.Context, value: Float) {
        prefs(context).edit().putFloat(SPEED, value.coerceIn(0.5f, 4f)).apply()
    }
    fun displayMode(context: android.content.Context) = prefs(context).getString(DISPLAY_MODE, "Fit") ?: "Fit"
    fun setDisplayMode(context: android.content.Context, value: String) {
        prefs(context).edit().putString(DISPLAY_MODE, value).apply()
    }
    fun autoNext(context: android.content.Context) = prefs(context).getBoolean(AUTO_NEXT, true)
    fun setAutoNext(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(AUTO_NEXT, value).apply()
    }
    fun cinematicEnabled(context: android.content.Context) = prefs(context).getBoolean(CINEMATIC_ENABLED, false)
    fun setCinematicEnabled(context: android.content.Context, value: Boolean) {
        prefs(context).edit().putBoolean(CINEMATIC_ENABLED, value).apply()
    }
    fun cinematicIntensity(context: android.content.Context) = prefs(context).getFloat(CINEMATIC_INTENSITY, 0.35f).coerceIn(0.05f, 0.8f)
    fun setCinematicIntensity(context: android.content.Context, value: Float) {
        prefs(context).edit().putFloat(CINEMATIC_INTENSITY, value.coerceIn(0.05f, 0.8f)).apply()
    }
    fun stretchScale(context: android.content.Context) = prefs(context).getFloat(STRETCH_SCALE, 1f).coerceIn(0.45f, 2.5f)
    fun setStretchScale(context: android.content.Context, value: Float) {
        prefs(context).edit().putFloat(STRETCH_SCALE, value.coerceIn(0.45f, 2.5f)).apply()
    }
}

@OptIn(UnstableApi::class)
internal object FullscreenVideoBridge {
    var player: ExoPlayer? = null
    var closeFullscreen: (() -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    val cinematicIntensity = mutableFloatStateOf(0f)
}

/**
 * Dedicated immersive, rotatable fullscreen video player.
 *
 * Lives in its own activity (rather than a Compose `Dialog` inside the feed) on purpose: forcing an
 * orientation change relayouts the host `LazyColumn`, which disposes the inline item — and a dialog
 * parented to that item would die with it. A separate activity has its own window, lifecycle and
 * orientation, so it survives rotation cleanly. It renders the *same* [ExoPlayer] handed over via
 * [FullscreenVideoBridge], so closing returns to the inline player exactly where it left off.
 */
@OptIn(UnstableApi::class)
class FullscreenPlayerActivity : ComponentActivity() {

    companion object {
        /** Float extra: the clip's width/height, used to pick the launch orientation. */
        const val EXTRA_ASPECT = "com.anisync.android.extra.ASPECT"

        /** Smallest width at which the window fits the clip in either orientation. */
        private const val LARGE_SCREEN_SW_DP = 600
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val aspect = intent.getFloatExtra(EXTRA_ASPECT, DEFAULT_VIDEO_ASPECT)
        // A phone only fits the clip one way round, so rotate it there. A tablet or unfolded
        // foldable has room either way and is often docked or held in portrait, so forcing a
        // rotation there fights the user for nothing (Android 16 ignores the request on those
        // windows anyway). Sensor variants still allow a 180° flip.
        val largeScreen = resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_SW_DP
        requestedOrientation = when {
            largeScreen -> ActivityInfo.SCREEN_ORIENTATION_USER
            aspect >= 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }

        // Edge-to-edge + hide the system bars for a true immersive view (swipe brings them back).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val player = FullscreenVideoBridge.player
        if (player == null) {
            // Nothing to show (e.g. process death restored this activity) — bail back to the app.
            finish()
            return
        }

        setContent {
            AppTheme(darkTheme = true) {
                FullscreenVideoContent(
                    player = player,
                    aspectRatio = aspect,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the shared reference; the inline player keeps its own and reattaches on return.
        FullscreenVideoBridge.player = null
        FullscreenVideoBridge.closeFullscreen = null
    }
}

/**
 * Fullscreen content: the shared [player] rendered into a fitted surface with the same auto-hiding
 * [VideoControlsOverlay] used inline. Tracks playback off its own listener so the controls stay live.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FullscreenVideoContent(
    player: ExoPlayer,
    aspectRatio: Float,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        FullscreenVideoBridge.closeFullscreen = onClose
        onDispose { FullscreenVideoBridge.closeFullscreen = null }
    }

    var isMuted by remember { mutableStateOf(player.volume == 0f) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var bufferedPositionMs by remember { mutableLongStateOf(player.bufferedPosition.coerceAtLeast(0)) }
    var isScrubbing by remember { mutableStateOf(false) }
    var playerState by remember {
        mutableStateOf(mapPlaybackState(player.playbackState, PlayerState.Loading))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var stretchX by remember { mutableFloatStateOf(PlayerPreferences.stretchScale(context)) }
    var isStretching by remember { mutableStateOf(false) }
    var snappedRatio by remember { mutableStateOf<Float?>(null) }
    var cinematicIntensity by remember { mutableFloatStateOf(if (PlayerPreferences.cinematicEnabled(context)) PlayerPreferences.cinematicIntensity(context) else 0f) }
    var displayMode by remember { mutableStateOf(PlayerPreferences.displayMode(context)) }
    // Seeded from the launch extra, then corrected off the player itself, so the fitted box tracks
    // the real picture even when the inline player never reported a size before the handoff.
    var aspect by remember {
        mutableFloatStateOf(aspectRatio.takeIf { it > 0f } ?: DEFAULT_VIDEO_ASPECT)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }

            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) FullscreenVideoBridge.onPlaybackEnded?.invoke()
                if (playbackState == Player.STATE_READY) {
                    val d = player.duration
                    if (d > 0) durationMs = d
                }
                playerState = mapPlaybackState(playbackState, playerState)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player.playbackState != Player.STATE_IDLE) return
                playerState = PlayerState.Error
                errorMessage = playbackErrorMessage(context, error)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Drive the scrubber while playing (paused while dragging).
    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            positionMs = player.currentPosition
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L)
            val d = player.duration
            if (d > 0) durationMs = d
            delay(250)
        }
    }

    // Pause when this activity is backgrounded; resume if it was playing.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.pause()
                Lifecycle.Event.ON_RESUME -> if (isPlaying) player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(aspect) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var baseSpan = 0f
                    var baseStretch = stretchX
                    var pinched = false
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.filter { it.pressed }
                        if (fingers.size >= 2) {
                            val span = kotlin.math.abs(fingers[0].position.x - fingers[1].position.x)
                            if (baseSpan == 0f) {
                                baseSpan = span.coerceAtLeast(1f)
                                baseStretch = stretchX
                                pinched = true
                                isStretching = true
                            } else {
                                var next = (baseStretch * span / baseSpan).coerceIn(0.45f, 2.5f)
                                val targetAspect = aspect * next
                                val ratios = listOf(16f / 9f, 3f / 2f, 4f / 3f, 1f)
                                val nearest = ratios.minByOrNull { kotlin.math.abs(targetAspect - it) }
                                val distancePx = nearest?.let { kotlin.math.abs(targetAspect - it) * size.height } ?: Float.MAX_VALUE
                                if (nearest != null && distancePx <= 18.dp.toPx()) {
                                    next = (nearest / aspect).coerceIn(0.45f, 2.5f)
                                    snappedRatio = nearest
                                } else snappedRatio = null
                                stretchX = next
                                PlayerPreferences.setStretchScale(context, next)
                            }
                            fingers.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinched) {
                        isStretching = false
                        snappedRatio = null
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Fit a box to the clip rather than letting the surface letterbox itself inside the whole
        // window. On a phone the two are the same box; on a tablet — especially in portrait, where
        // a wide clip fills a band across the middle — it keeps the controls on the video instead
        // of stranding the scrubber at the bottom of the screen, far from the picture.
        val videoModifier = Modifier.fillMaxSize()

        if (cinematicIntensity > 0f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFFC47A).copy(alpha = cinematicIntensity * 0.34f), Color(0xFF8B5CF6).copy(alpha = cinematicIntensity * 0.12f), Color.Transparent),
                        radius = 1400f
                    )
                )
            )
        }
        Box(videoModifier) {
            if (playerState != PlayerState.Error) {
                PlayerSurface(
                    exoPlayer = player,
                    active = true,
                    modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = stretchX),
                    contentScale = when (displayMode) {
                        "Isi layar" -> ContentScale.Crop
                        "Stretch" -> ContentScale.FillBounds
                        else -> ContentScale.Fit
                    }
                )
            }
        }
        if (isStretching) {
            Canvas(Modifier.fillMaxSize()) {
                val guideRatios = listOf(16f / 9f, 3f / 2f, 4f / 3f, 1f)
                guideRatios.forEach { ratio ->
                    val guideWidth = minOf(size.width, size.height * ratio)
                    val guideHeight = guideWidth / ratio
                    val isActive = snappedRatio == ratio
                    drawRect(
                        color = if (isActive) Color(0xFFFF8A3D) else Color.White.copy(alpha = 0.48f),
                        topLeft = Offset((size.width - guideWidth) / 2f, (size.height - guideHeight) / 2f),
                        size = Size(guideWidth, guideHeight),
                        style = Stroke(width = if (isActive) 3.dp.toPx() else 1.dp.toPx())
                    )
                }
            }
        }

        PlayerStatusVisuals(
            isLoading = playerState == PlayerState.Loading,
            isBuffering = playerState == PlayerState.Buffering,
            errorMessage = if (playerState == PlayerState.Error) errorMessage else null,
            showShimmer = false,
            onRetry = {
                playerState = PlayerState.Loading
                errorMessage = null
                player.prepare()
            }
        )

        if (playerState == PlayerState.Ready || playerState == PlayerState.Buffering) {
            VideoControlsOverlay(
                modifier = videoModifier,
                isPlaying = isPlaying,
                isBuffering = playerState == PlayerState.Buffering,
                isMuted = isMuted,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                isFullscreen = true,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onToggleMute = {
                    isMuted = !isMuted
                    player.volume = if (isMuted) 0f else 1f
                },
                onSeek = { fraction ->
                    isScrubbing = true
                    val d = durationMs
                    if (d > 0) {
                        positionMs = (fraction * d).toLong()
                        player.seekTo(positionMs)
                    }
                },
                onSeekBy = { delta ->
                    val target = (player.currentPosition + delta).coerceIn(0L, durationMs.coerceAtLeast(0L))
                    player.seekTo(target)
                    positionMs = target
                },
                onSeekFinished = { isScrubbing = false },
                onSetPlaybackSpeed = { player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(it, 1f)) },
                sourceOptions = emptyList(),
                onSelectSource = {},
                onCinematicLightingChange = { intensity ->
                    cinematicIntensity = intensity
                    FullscreenVideoBridge.cinematicIntensity.floatValue = intensity
                },
                onResetStretch = { stretchX = 1f; PlayerPreferences.setStretchScale(context, 1f) },
                displayMode = displayMode,
                onDisplayModeChange = { displayMode = it; PlayerPreferences.setDisplayMode(context, it) },
                onToggleFullscreen = onClose,
                onBack = onClose
            )
        }
    }
}

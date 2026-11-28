package com.anisync.android.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * Inline YouTube embeds remain lightweight thumbnail previews, as in the original AniSync
 * behavior. Tapping a thumbnail opens the native Media3 player dialog.
 */
@Composable
fun YouTubeInlinePlayer(
    videoId: String,
    modifier: Modifier = Modifier
) {
    val safeId = remember(videoId) {
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    } ?: return
    val context = LocalContext.current
    // YouTube thumbnails use the same isolated transport as direct YouTube playback,
    // not the app-wide image client configured for unrelated CDNs.
    val thumbnailLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { YouTubeStreamResolver.playbackHttpClient }))
            }
            .build()
    }
    var showPlayer by remember(safeId) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Color.Black)
            .clickable { showPlayer = true },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = "https://i.ytimg.com/vi/$safeId/hqdefault.jpg",
            imageLoader = thumbnailLoader,
            contentDescription = "Pratinjau video YouTube",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.72f)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Putar video",
                tint = Color.White,
                modifier = Modifier.size(56.dp).padding(10.dp)
            )
        }
    }

    if (showPlayer) {
        YouTubePlayerDialog(
            videoId = safeId,
            onDismiss = { showPlayer = false }
        )
    }
}

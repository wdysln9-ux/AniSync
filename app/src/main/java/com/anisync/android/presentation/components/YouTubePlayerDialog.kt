package com.anisync.android.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Extracts a direct YouTube media stream and plays it with Media3, rather than embedding
 * YouTube's HTML iframe in a WebView. This follows the extract-then-native-playback architecture
 * used by AnimeW while retaining AniSync's own player controls and fullscreen handoff.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun YouTubePlayerDialog(
    videoId: String,
    onDismiss: () -> Unit,
    title: String? = null
) {
    val safeVideoId = videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    if (safeVideoId == null) return

    BackHandler(onBack = onDismiss)

    var resolved by remember(safeVideoId) {
        mutableStateOf<YouTubeStreamResolver.Resolved?>(null)
    }
    var error by remember(safeVideoId) { mutableStateOf<String?>(null) }
    var loading by remember(safeVideoId) { mutableStateOf(true) }

    LaunchedEffect(safeVideoId) {
        loading = true
        error = null
        resolved = null
        try {
            resolved = YouTubeStreamResolver.resolve(
                "https://www.youtube.com/watch?v=$safeVideoId"
            )
        } catch (failure: Throwable) {
            error = failure.message ?: "Video YouTube gagal dimuat."
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF101010),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title?.takeIf { it.isNotBlank() } ?: "YouTube",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Tutup pemutar",
                            tint = Color.White
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center
                ) {
                    val stream = resolved
                    when {
                        stream != null -> VideoPlayer(
                            url = stream.url,
                            headers = stream.headers,
                            modifier = Modifier.fillMaxSize(),
                            playerCache = null,
                            startMuted = false,
                            loop = false,
                            autoPlay = true
                        )
                        loading -> CircularProgressIndicator(color = Color.White)
                        else -> Text(
                            text = error ?: "Video YouTube tidak tersedia.",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

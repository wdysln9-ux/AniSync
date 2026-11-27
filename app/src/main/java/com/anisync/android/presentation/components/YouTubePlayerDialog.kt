package com.anisync.android.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.util.Log
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Plays a YouTube video in an in-app WebView using YouTube's official embed player.
 * The video id is validated before it is inserted into the iframe URL.
 */
@Composable
fun YouTubePlayerDialog(
    videoId: String,
    onDismiss: () -> Unit,
    title: String? = null
) {
    val safeVideoId = videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    if (safeVideoId == null) return

    BackHandler(onBack = onDismiss)

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
                Row(
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

                key(safeVideoId) {
                    YouTubeWebView(
                        videoId = safeVideoId,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeWebView(
    videoId: String,
    modifier: Modifier = Modifier
) {
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    // Use Android's reserved HTTPS WebView origin. A direct loadUrl(embedUrl) request
    // has no meaningful embedding-page Referer; spoofing youtube.com as Referer/Origin
    // identifies the wrong client and can leave the official player completely black.
    val webOrigin = "https://appassets.androidplatform.net"
    val encodedOrigin = remember { android.net.Uri.encode(webOrigin) }

    val html = remember(videoId, encodedOrigin) {
        """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              background: #000;
              overflow: hidden;
            }
            iframe {
              position: absolute;
              inset: 0;
              width: 100%;
              height: 100%;
              border: 0;
            }
          </style>
        </head>
        <body>
          <iframe
            src="https://www.youtube.com/embed/$videoId?playsinline=1&controls=1&rel=0&enablejsapi=1&origin=$encodedOrigin"
            title="YouTube video player"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
            referrerpolicy="strict-origin-when-cross-origin"
            allowfullscreen>
          </iframe>
        </body>
        </html>
        """.trimIndent()
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewState.value = this
                    setBackgroundColor(AndroidColor.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            Log.d("AniSyncYouTube", "Player document loaded: $url")
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                Log.e(
                                    "AniSyncYouTube",
                                    "Player page failed: ${request.url} (code=${error.errorCode}, ${error.description})"
                                )
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            response: android.webkit.WebResourceResponse
                        ) {
                            super.onReceivedHttpError(view, request, response)
                            Log.w(
                                "AniSyncYouTube",
                                "HTTP ${response.statusCode}: ${request.url}"
                            )
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            Log.w(
                                "AniSyncYouTube",
                                "JS ${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                            )
                            return true
                        }
                    }

                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadDataWithBaseURL(
                        "$webOrigin/",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { _ ->
                // Keep the WebView document alive during Compose recompositions.
            }
        )
    }

    DisposableEffect(webViewState.value) {
        val webView = webViewState.value
        onDispose {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.removeAllViews()
            webView?.destroy()
        }
    }
}

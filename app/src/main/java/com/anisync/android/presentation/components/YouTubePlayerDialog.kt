package com.anisync.android.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    // YouTube requires the installed app's identifier as the HTTP Referer for WebView embeds.
    // Using youtube.com here identifies YouTube itself, not AniSync, and can cause error 153.
    val appOrigin = remember(context) { "https://${context.packageName}" }
    val encodedAppOrigin = remember(appOrigin) { Uri.encode(appOrigin) }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewState.value = this
                    setBackgroundColor(AndroidColor.BLACK)
                    // Keep video compositing on the hardware layer. Some Android WebView
                    // versions otherwise show the embedded video surface as a black rectangle.
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadsImagesAutomatically = true
                    settings.databaseEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            Log.w(
                                "AniSyncYouTube",
                                "Web resource failed: ${request.url} (code=${error.errorCode})"
                            )
                        }

                        override fun onReceivedHttpError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                            errorResponse: android.webkit.WebResourceResponse
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            Log.w(
                                "AniSyncYouTube",
                                "HTTP ${errorResponse.statusCode} for ${request.url}"
                            )
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            Log.w(
                                "AniSyncYouTube",
                                "${message.message()} (line ${message.lineNumber()})"
                            )
                            return true
                        }
                    }
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // The HTTPS host MUST be the installed Android application ID, not
                    // youtube.com. YouTube's embedded-player policy requires the app ID in
                    // HTTP Referer for WebView clients; using YouTube's own host identifies
                    // the wrong client and can trigger error 153 / a permanently black player.
                    // loadDataWithBaseURL supplies that Referer to the iframe request.
                    val html = """
                        <!doctype html>
                        <html>
                        <head>
                          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                          <style>
                            html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                            iframe { position:absolute; inset:0; width:100%; height:100%; border:0; }
                          </style>
                        </head>
                        <body>
                          <iframe
                            src="https://www.youtube.com/embed/$videoId?autoplay=0&playsinline=1&controls=1&rel=0&enablejsapi=1&origin=$encodedAppOrigin"
                            title="YouTube video player"
                            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                            referrerpolicy="strict-origin-when-cross-origin"
                            allowfullscreen>
                          </iframe>
                        </body>
                        </html>
                    """.trimIndent()
                    loadDataWithBaseURL(
                        "$appOrigin/",
                        html,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { _ -> }
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

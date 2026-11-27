package com.anisync.android.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Inline YouTube player using the same official iframe setup as the trailer dialog.
 * Do not spoof YouTube as the embedding page's Referer or Origin.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeInlinePlayer(
    videoId: String,
    modifier: Modifier = Modifier
) {
    val safeId = remember(videoId) {
        videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    } ?: return

    val webViewState = remember { mutableStateOf<WebView?>(null) }
    val webOrigin = "https://appassets.androidplatform.net"
    val encodedOrigin = remember { android.net.Uri.encode(webOrigin) }
    val html = remember(safeId, encodedOrigin) {
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
            src="https://www.youtube.com/embed/$safeId?playsinline=1&controls=1&rel=0&enablejsapi=1&origin=$encodedOrigin"
            title="YouTube video player"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
            referrerpolicy="strict-origin-when-cross-origin"
            allowfullscreen>
          </iframe>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        factory = { context ->
            WebView(context).apply {
                webViewState.value = this
                setBackgroundColor(AndroidColor.BLACK)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.databaseEnabled = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.allowContentAccess = false
                settings.allowFileAccess = false

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d("AniSyncYouTube", "Inline player document loaded: $url")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e(
                            "AniSyncYouTube",
                            "Inline load failed: \${request.url}, code=\${error.errorCode}, \${error.description}"
                        )
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                        response: android.webkit.WebResourceResponse
                    ) {
                        super.onReceivedHttpError(view, request, response)
                        Log.e("AniSyncYouTube", "Inline HTTP \${response.statusCode}: \${request.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        Log.e(
                            "AniSyncYouTube",
                            "Inline JS \${message.message()} at \${message.sourceId()}:\${message.lineNumber()}"
                        )
                        return true
                    }
                }

                isFocusable = true
                isFocusableInTouchMode = true
                loadDataWithBaseURL("$webOrigin/", html, "text/html", "UTF-8", null)
            }
        },
        update = {
            // Keep the iframe alive across Compose recompositions.
        }
    )

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

package com.anisync.android.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.unit.dp

/**
 * Inline YouTube embed matching AnimeW's working WebView implementation:
 * direct youtube-nocookie embed URL, Android mobile browser UA, and the same
 * Referer/Origin request headers used by AnimeW's WebViewController.loadRequest.
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
    val embedUrl = remember(safeId) {
        "https://www.youtube-nocookie.com/embed/$safeId?playsinline=1"
    }
    val requestHeaders = remember {
        mapOf(
            "Referer" to "https://www.youtube.com/",
            "Origin" to "https://www.youtube.com"
        )
    }
    val mobileUserAgent = remember {
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
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
                // Keep the inline WebView on its own hardware layer. The dialog player uses
                // the same setting; without it, Chromium's video surface can composite as a
                // permanently black rectangle inside Compose's AndroidView on some devices.
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.databaseEnabled = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.userAgentString = mobileUserAgent

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        android.util.Log.d("AniSyncYouTube", "Embed loaded: $url")
                    }
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        super.onReceivedError(view, request, error)
                        android.util.Log.e("AniSyncYouTube", "Load failed: " + request.url + ", code=" + error.errorCode + ", " + error.description)
                    }
                    override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                        super.onReceivedHttpError(view, request, response)
                        android.util.Log.e("AniSyncYouTube", "HTTP " + response.statusCode + ": " + request.url)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.e("AniSyncYouTube", "JS " + message.message() + " at " + message.sourceId() + ":" + message.lineNumber())
                        return true
                    }
                }

                isFocusable = true
                isFocusableInTouchMode = true
                loadUrl(embedUrl, requestHeaders)
            }
        },
        update = { webView ->
            // Keep the same WebView alive through recompositions. Only navigate if
            // its current document is not already the requested video.
            val current = webView.url
            if (current == null || current == "about:blank" || !current.contains(safeId)) {
                webView.loadUrl(embedUrl, requestHeaders)
            }
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

package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Clean-room `CloudflareKiller` — the WebView-based Cloudflare solver that lets
 * CF-gated sources (AnimePahe, …) work, mirroring CloudStream's approach.
 *
 * When a response is the Cloudflare "Just a moment…" challenge, it loads the URL
 * in a hidden WebView so Cloudflare's JS runs, harvests the `cf_clearance`
 * cookie (+ the WebView's User-Agent), then replays the request with them.
 * Solved cookies are reused per-host until they expire (a later challenge
 * re-solves). Safe by design: for any NON-challenge response it returns the
 * response untouched, and every step is guarded — a failure just yields the
 * original (blocked) response, exactly like before.
 */
class CloudflareKiller : Interceptor {
    // Kept for binary compat with plugins that read it; we manage cookies below.
    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    // Per-instance: only carries a just-solved cookie into the retry within the
    // SAME intercept() call. Cross-CALL cookie reuse is the shared WebView cookie
    // store's job (WebkitCookieJar) + the persisted CfClearance.userAgent — not
    // this map. Kept per-instance on purpose: a static cookie map would wedge a
    // rotated/expired cf_clearance until app restart. The negative cache + per-host
    // lock ARE shared (companion) — with a fresh instance per request, a
    // per-instance backoff was lost, re-popping a 30s "verifying" on every click.
    private val cookieByHost = ConcurrentHashMap<String, String>()

    /**
     * Cookie headers for [url]'s host — the Cloudflare cookies (cf_clearance, …)
     * from the shared WebView store plus the User-Agent the challenge was solved
     * with. Some plugins (e.g. FaselHD) call `CloudflareKiller().getCookieHeaders(url)`
     * to authenticate their own requests; without this method they hit
     * NoSuchMethodError in loadLinks and return zero sources ("no source playable").
     * ADDITIVE — no existing code path calls it, so it can't change current
     * behaviour for any other plugin.
     */
    fun getCookieHeaders(url: String): okhttp3.Headers {
        val builder = okhttp3.Headers.Builder()
        com.anisync.android.cloudstream.CfClearance.userAgent
            ?.let { builder.add("User-Agent", it) }
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.add("Cookie", it) }
        return builder.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        val response = chain.proceed(applySaved(request, host))
        if (!isCloudflareChallenge(response)) return response

        // During a provider search, NEVER pop the WebView solver (CloudStream
        // doesn't either): return the challenge unsolved so this source simply
        // yields no search hits. An already-cached clearance was applied above,
        // so CF sources solved earlier (on open/play) still search silently.
        if (com.anisync.android.cloudstream.CfClearance.searchDepth.get() > 0) return response

        // The URL that actually produced the challenge — AFTER any redirects
        // (e.g. animepahe.com → animepahe.pw). The clearance cookie belongs to
        // THIS host, and we retry against THIS url so okhttp can't strip the
        // cookie across a cross-domain redirect.
        val finalUrl = response.request.url
        val finalHost = finalUrl.host
        synchronized(locks.getOrPut(finalHost) { Any() }) {
            if (!cookieByHost.containsKey(finalHost)) {
                // Negative cache: a host whose solve just failed isn't retried
                // (no solver popup) for ~30 min — just return the challenge.
                val until = failedUntil[finalHost]
                if (until != null && System.currentTimeMillis() < until) return response
                val solved = CfWebViewSolver.solve(finalUrl.toString())
                if (solved == null) {
                    failedUntil[finalHost] = System.currentTimeMillis() + 30 * 60 * 1000L
                    return response // give back the (unconsumed) challenge
                }
                failedUntil.remove(finalHost) // solved → clear any backoff
                cookieByHost[finalHost] = solved.cookie
                com.anisync.android.cloudstream.CfClearance.userAgent = solved.userAgent
            }
        }
        response.close()
        val cookie = cookieByHost[finalHost]
        val retried = request.newBuilder()
            .url(finalUrl)
            .apply {
                if (cookie != null) {
                    val existing = request.header("Cookie")
                    header("Cookie", if (existing.isNullOrBlank()) cookie else "$existing; $cookie")
                }
                com.anisync.android.cloudstream.CfClearance.userAgent?.let { header("User-Agent", it) }
            }
            .build()
        return chain.proceed(retried)
    }

    /** Attach the harvested clearance cookie (+ the solving UA it's bound to) for
     *  [host], if this instance solved it in-call. Only touches requests we have a
     *  cookie for, so non-CF traffic keeps its own UA. */
    private fun applySaved(request: Request, host: String): Request {
        val cookie = cookieByHost[host] ?: return request
        val b = request.newBuilder()
        val existing = request.header("Cookie")
        b.header("Cookie", if (existing.isNullOrBlank()) cookie else "$existing; $cookie")
        com.anisync.android.cloudstream.CfClearance.userAgent?.let { b.header("User-Agent", it) }
        return b.build()
    }

    /** True when [response] is a Cloudflare interstitial challenge page. Only
     * peeks HTML-ish bodies so JSON/media responses are cheap. */
    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server").orEmpty().lowercase()
        val cfMitigated = response.header("cf-mitigated") != null
        val statusFlag = response.code in intArrayOf(403, 503, 429) &&
            server.contains("cloudflare")
        val ct = response.header("Content-Type").orEmpty().lowercase()
        val htmlish = ct.contains("text/html") || ct.isEmpty()
        if (!htmlish && !cfMitigated && !statusFlag) return false
        val body = try {
            response.peekBody(20_000L).string() // peek doesn't consume the body
        } catch (_: Exception) {
            return cfMitigated || statusFlag
        }
        return cfMitigated || statusFlag ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenge-platform") ||
            body.contains("challenges.cloudflare.com") ||
            body.contains("_cf_chl_opt") ||
            body.contains("cf-browser-verification")
    }

    companion object {
        // Shared across every CloudflareKiller instance (plugins make a fresh one
        // per request). host -> epoch-ms until which we won't re-attempt a solve:
        // a CF host we can't clear (e.g. an interactive challenge) would otherwise
        // pop a 30s "verifying" on EVERY request. The per-host lock is shared too so
        // concurrent requests for one host don't each launch a solver.
        private val failedUntil = ConcurrentHashMap<String, Long>()
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

/** Loads a URL in a hidden WebView and waits for Cloudflare's `cf_clearance`
 * cookie. WebView work runs on the main thread; the (background) caller blocks
 * on a latch with a timeout. */
object CfWebViewSolver {
    data class Result(val cookie: String, val userAgent: String)

    // The interactive WebView solver is intentionally disabled in this build.
    // Keep the API expected by CloudflareKiller while avoiding references to
    // Activity classes that belong to the original CloudStream host application.
    fun solve(url: String): Result? = null
}

package com.anisync.android.presentation.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube page to a direct, muxed media URL for Media3.
 *
 * This is the Android-native equivalent of AnimeW's extract-then-play flow: extraction happens
 * off the main thread and the resulting media is rendered by the app's existing Media3 player.
 */
internal object YouTubeStreamResolver {
    data class Resolved(
        val title: String,
        val url: String,
        val headers: Map<String, String>
    )

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    @Volatile
    private var initialized = false

    /**
     * YouTube media and thumbnail requests deliberately bypass MediaHttp's shared CDN client.
     * That client pins HTTP/1.1 and enables ECH for unrelated hosts; AnimeW's media_kit path
     * uses its own native transport. Keep YouTube on a plain OkHttp transport instead.
     */
    val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val initLock = Any()
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloader = object : Downloader() {
        override fun execute(request: ExtractorRequest): ExtractorResponse {
            val builder = okhttp3.Request.Builder()
                .url(request.url())
                .header("User-Agent", USER_AGENT)

            request.headers().orEmpty().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }

            val method = request.httpMethod().uppercase()
            val payload = request.dataToSend()
            val body = payload?.toRequestBody("application/octet-stream".toMediaType())
            builder.method(method, body)

            http.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                return ExtractorResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    responseBody,
                    response.request.url.toString()
                )
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (!initialized) {
                NewPipe.init(downloader)
                initialized = true
            }
        }
    }

    suspend fun resolve(pageUrl: String): Resolved = withContext(Dispatchers.IO) {
        initialize()
        val info = StreamInfo.getInfo(pageUrl)
        // AnimeW resolves manifest.muxed: a progressive stream containing BOTH video
        // and audio. NewPipe exposes the equivalent entries in videoStreams; explicitly
        // reject adaptive/video-only entries and prefer the highest muxed bitrate.
        val stream = info.videoStreams
            .asSequence()
            .filter {
                it.isUrl &&
                    !it.isVideoOnly &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
            .maxWithOrNull(compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.bitrate }
                .thenBy { it.height })
            ?: throw IOException(
                "YouTube tidak menyediakan stream video+audio yang bisa diputar langsung."
            )

        val directUrl = stream.content
        if (directUrl.isBlank()) throw IOException("URL video YouTube kosong.")

        Resolved(
            title = info.name,
            url = directUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://www.youtube.com/"
            )
        )
    }
}

package com.anisync.android.data

import android.os.SystemClock
import android.util.LruCache
import com.anisync.android.GetLinkPreviewsQuery
import com.anisync.android.GetUserPreviewQuery
import com.anisync.android.data.local.dao.MediaDetailsDao
import com.anisync.android.domain.LinkPreview
import com.anisync.android.domain.LinkPreviewProvider
import com.anisync.android.domain.parser.LinkPreviewKey
import com.anisync.android.domain.parser.RichTextBlock
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves AniList link previews through one application-wide queue.
 *
 * Activities and feed cards often enter composition together. Keeping the queue here means their
 * IDs can be combined into the same GraphQL request instead of each RichTextHost issuing its own
 * request. The bounded memory cache and in-flight map also prevent the same link being fetched
 * again by another activity or another card.
 */
@Singleton
class LinkPreviewProviderImpl @Inject constructor(
    private val mediaDetailsDao: MediaDetailsDao,
    private val apolloClient: ApolloClient
) : LinkPreviewProvider {

    private val cache = LruCache<LinkPreviewKey, LinkPreview>(500)
    private val inFlight = mutableMapOf<LinkPreviewKey, CompletableDeferred<LinkPreview?>>()
    private val queue = Channel<RichTextBlock.AnilistLink>(Channel.UNLIMITED)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNetworkRequestAt = 0L

    init {
        workerScope.launch {
            for (first in queue) {
                // Give other visible activities a short window to contribute IDs to this batch.
                delay(BATCH_WINDOW_MS)
                val batch = linkedMapOf<LinkPreviewKey, RichTextBlock.AnilistLink>()
                batch[first.previewKey] = first
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch.putIfAbsent(next.previewKey, next)
                }
                resolveBatch(batch.values.toList())
            }
        }
    }

    override suspend fun getPreviews(
        links: List<RichTextBlock.AnilistLink>
    ): Map<LinkPreviewKey, LinkPreview> {
        if (links.isEmpty()) return emptyMap()

        val uniqueLinks = links.distinctBy { it.previewKey }
        val result = mutableMapOf<LinkPreviewKey, LinkPreview>()
        val unresolved = mutableListOf<RichTextBlock.AnilistLink>()

        // Reuse the persistent media-details database before entering the network queue.
        for (link in uniqueLinks) {
            val memoryHit = synchronized(cache) { cache.get(link.previewKey) }
            if (memoryHit != null) {
                result[link.previewKey] = memoryHit
                continue
            }

            if (link.type == "anime" || link.type == "manga") {
                val entity = mediaDetailsDao.getById(link.id)
                if (entity != null && entity.coverColor != null) {
                    val preview = LinkPreview(
                        title = entity.titleUserPreferred,
                        imageUrl = entity.coverUrl,
                        coverColor = entity.coverColor,
                        subtitle = buildMediaSubtitle(
                            format = entity.format,
                            season = entity.season,
                            year = entity.seasonYear ?: entity.year,
                            type = entity.mediaType?.name,
                            episodes = entity.episodes,
                            chapters = entity.chapters,
                            score = entity.score
                        ),
                        score = entity.score,
                        genres = entity.genres
                    )
                    synchronized(cache) { cache.put(link.previewKey, preview) }
                    result[link.previewKey] = preview
                    continue
                }
            }
            unresolved += link
        }

        // Each caller joins the same deferred for a key already queued or currently fetching.
        val pending = unresolved.map { link -> link.previewKey to enqueue(link) }
        for ((key, deferred) in pending) {
            deferred.await()?.let { result[key] = it }
        }
        return result
    }

    private fun enqueue(link: RichTextBlock.AnilistLink): CompletableDeferred<LinkPreview?> {
        synchronized(inFlight) {
            synchronized(cache) { cache.get(link.previewKey) }?.let {
                return CompletableDeferred(it)
            }
            inFlight[link.previewKey]?.let { return it }

            val deferred = CompletableDeferred<LinkPreview?>()
            inFlight[link.previewKey] = deferred
            val sent = queue.trySend(link)
            if (sent.isFailure) {
                inFlight.remove(link.previewKey)
                deferred.complete(null)
            }
            return deferred
        }
    }

    private suspend fun resolveBatch(links: List<RichTextBlock.AnilistLink>) {
        val resolved = mutableMapOf<LinkPreviewKey, LinkPreview>()
        val media = links.filter { it.type == "anime" || it.type == "manga" }
        val characters = links.filter { it.type == "character" }
        val staff = links.filter { it.type == "staff" }
        val users = links.filter { it.type == "user" }

        // The AniList query accepts up to 25 IDs per category. Keep each request within that
        // limit while filling all three categories in parallel in the same GraphQL operation.
        val chunkCount = maxOf(
            media.size,
            characters.size,
            staff.size
        ).let { (it + MAX_IDS_PER_QUERY - 1) / MAX_IDS_PER_QUERY }

        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * MAX_IDS_PER_QUERY
            val mediaIds = media.drop(start).take(MAX_IDS_PER_QUERY).map { it.id }.distinct()
            val characterIds = characters.drop(start).take(MAX_IDS_PER_QUERY).map { it.id }.distinct()
            val staffIds = staff.drop(start).take(MAX_IDS_PER_QUERY).map { it.id }.distinct()

            try {
                awaitRequestSlot()
                val response = apolloClient.query(
                    GetLinkPreviewsQuery(
                        mediaIds = Optional.presentIfNotNull(mediaIds.takeIf { it.isNotEmpty() }),
                        characterIds = Optional.presentIfNotNull(characterIds.takeIf { it.isNotEmpty() }),
                        staffIds = Optional.presentIfNotNull(staffIds.takeIf { it.isNotEmpty() })
                    )
                ).execute()

                response.data?.mediaPage?.media?.filterNotNull()?.forEach { item ->
                    val id = item.id
                    val type = item.type?.name?.lowercase() ?: "anime"
                    val preview = LinkPreview(
                        title = item.title?.userPreferred ?: return@forEach,
                        imageUrl = item.coverImage?.large,
                        coverColor = item.coverImage?.color,
                        subtitle = buildMediaSubtitle(
                            format = item.format?.rawValue,
                            season = item.season?.rawValue,
                            year = item.seasonYear,
                            type = item.type?.name,
                            episodes = item.episodes,
                            chapters = item.chapters,
                            score = item.averageScore
                        ),
                        score = item.averageScore,
                        genres = item.genres?.filterNotNull().orEmpty()
                    )
                    val requestedKey = media.firstOrNull { it.id == id }?.previewKey ?: LinkPreviewKey(type, id)
                    resolved[requestedKey] = preview
                }

                response.data?.characterPage?.characters?.filterNotNull()?.forEach { item ->
                    val preview = LinkPreview(
                        title = item.name?.userPreferred ?: return@forEach,
                        imageUrl = item.image?.large
                    )
                    resolved[LinkPreviewKey("character", item.id)] = preview
                }

                response.data?.staffPage?.staff?.filterNotNull()?.forEach { item ->
                    val preview = LinkPreview(
                        title = item.name?.userPreferred ?: return@forEach,
                        imageUrl = item.image?.large
                    )
                    resolved[LinkPreviewKey("staff", item.id)] = preview
                }
            } catch (_: Exception) {
                // Complete unresolved entries below; a later composition can retry them.
            }
        }

        // AniList does not support fetching users by ID list, so user links remain individual.
        for (link in users.distinctBy { it.previewKey }) {
            val name = link.slug ?: continue
            try {
                awaitRequestSlot()
                val user = apolloClient.query(GetUserPreviewQuery(Optional.present(name)))
                    .execute().data?.user ?: continue
                resolved[LinkPreviewKey("user", 0, name)] = LinkPreview(
                    title = user.name,
                    imageUrl = user.avatar?.large
                )
            } catch (_: Exception) {
                // A failed user preview falls back to its URL-derived label.
            }
        }

        synchronized(cache) {
            resolved.forEach { (key, preview) -> cache.put(key, preview) }
        }

        synchronized(inFlight) {
            links.distinctBy { it.previewKey }.forEach { link ->
                val preview = resolved[link.previewKey]
                inFlight.remove(link.previewKey)?.complete(preview)
            }
        }
    }

    /**
     * Preview requests are intentionally paced globally. The app also makes other AniList calls,
     * so preview traffic is kept well below the temporary 30-request/minute limit rather than
     * consuming the whole allowance when a feed with many activities opens.
     */
    private suspend fun awaitRequestSlot() {
        val now = SystemClock.elapsedRealtime()
        val wait = (MIN_REQUEST_INTERVAL_MS - (now - lastNetworkRequestAt)).coerceAtLeast(0L)
        if (lastNetworkRequestAt != 0L && wait > 0L) delay(wait)
        lastNetworkRequestAt = SystemClock.elapsedRealtime()
    }

    /** Media secondary line, e.g. "TV · Summer 2024 · 12 Episode". */
    private fun buildMediaSubtitle(
        format: String?,
        season: String?,
        year: Int?,
        type: String?,
        episodes: Int?,
        chapters: Int?,
        score: Int?
    ): String? {
        val seasonAndYear = listOfNotNull(
            season?.let(::prettySeason),
            year?.toString()
        ).joinToString(" ").takeIf { it.isNotBlank() }
        val count = when (type?.uppercase()) {
            "ANIME" -> episodes?.takeIf { it > 0 }?.let { "$it Episode" }
            "MANGA" -> chapters?.takeIf { it > 0 }?.let { "$it Chapter" }
            else -> null
        }
        val parts = listOfNotNull(
            format?.let(::prettyFormat),
            seasonAndYear,
            count,
            score?.takeIf { it > 0 }?.let { "$it%" }
        )
        return parts.joinToString(" • ").takeIf { it.isNotBlank() }
    }

    private fun prettySeason(raw: String): String = when (raw.uppercase()) {
        "WINTER" -> "Winter"
        "SPRING" -> "Spring"
        "SUMMER" -> "Summer"
        "FALL" -> "Fall"
        else -> raw.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun prettyFormat(raw: String): String = when (raw.uppercase()) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Pendek"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "MOVIE" -> "Movie"
        "SPECIAL" -> "Special"
        "MUSIC" -> "Music"
        "MANGA" -> "Manga"
        "NOVEL" -> "Light Novel"
        "ONE_SHOT" -> "One-shot"
        else -> raw.lowercase().replaceFirstChar { it.uppercase() }
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 25
        const val BATCH_WINDOW_MS = 150L
        const val MIN_REQUEST_INTERVAL_MS = 3_500L
    }
}

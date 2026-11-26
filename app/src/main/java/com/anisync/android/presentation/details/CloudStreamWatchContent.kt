package com.anisync.android.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anisync.android.cloudstream.CloudStreamRuntime
import com.anisync.android.presentation.components.VideoPlayer
import com.anisync.android.presentation.components.FullscreenVideoBridge
import com.anisync.android.presentation.details.components.ThemeSheet
import com.anisync.android.presentation.details.components.SheetLabel
import com.anisync.android.presentation.details.components.ThemeSheetIdentity
import com.anisync.android.presentation.details.components.SourceChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

private fun formatEpisodeReleaseDate(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return "Tanggal rilis belum tersedia"
    return try {
        val datePart = raw.take(10)
        LocalDate.parse(datePart).format(DateTimeFormatter.ofPattern("MMM, dd yyyy", Locale.ENGLISH))
    } catch (_: DateTimeParseException) {
        raw
    }
}

private data class ResolveHit(val provider: String, val item: Map<String, Any?>, val score: Int)
private data class AniZipEpisode(val title: String?, val summary: String?, val image: String?, val releaseDate: String? = null)
private object CloudStreamWatchCache { val anime = mutableMapOf<Int, Triple<ResolveHit, Map<String, Any?>, List<Map<String, Any?>>>>(); val streams = mutableMapOf<String, Pair<List<Map<String, Any?>>, List<Map<String, Any?>>>>() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudStreamWatchContent(
    mediaId: Int,
    mediaTitle: String,
    romajiTitle: String?,
    englishTitle: String?,
    synonyms: List<String>,
    malId: Int?,
    trackedProgress: Int? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("anisync_auto_resolve", 0) }
    val providerPriorityPrefs = remember(context) { context.getSharedPreferences("cloudstream_provider_priority", 0) }
    val aliases = remember(mediaTitle, romajiTitle, englishTitle, synonyms) {
        (listOfNotNull(romajiTitle, englishTitle, mediaTitle) + synonyms)
            .map(String::trim).filter(String::isNotBlank).distinctBy(::normalizeTitle).take(8)
    }
    var providers by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var hits by remember { mutableStateOf<List<ResolveHit>>(emptyList()) }
    var selected by remember { mutableStateOf<ResolveHit?>(null) }
    var anime by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var episodes by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var metadata by remember { mutableStateOf<Map<Int, AniZipEpisode>>(emptyMap()) }
    var episode by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var streams by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var subtitles by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var selectedStream by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var preloadStream by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var preloadRequestId by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }
    var sourceLoadRequestId by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Menyiapkan Auto Resolve…") }
    var error by remember { mutableStateOf<String?>(null) }
    var correction by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var autoNextEnabled by remember { mutableStateOf(true) }
    var autoNextFullscreen by remember { mutableStateOf(false) }
    var episodePopupVisible by remember { mutableStateOf(false) }
    var manualQuery by remember(mediaTitle) { mutableStateOf(mediaTitle) }

    fun loadHit(hit: ResolveHit, allowFallback: Boolean = true) {
        val requestId = ++sourceLoadRequestId
        selected = hit

        // Switching provider must detach every piece of episode/player state from the old source.
        anime = null
        episodes = emptyList()
        episode = null
        sheet = false
        streams = emptyList()
        subtitles = emptyList()
        selectedStream = null
        preloadStream = null
        preloadRequestId++
        FullscreenVideoBridge.episodeLabel.value = null
        error = null

        val cachedAnime = CloudStreamWatchCache.anime[mediaId]
        if (cachedAnime != null &&
            cachedAnime.first.provider == hit.provider &&
            cachedAnime.first.item["url"] == hit.item["url"]
        ) {
            anime = cachedAnime.second
            episodes = cachedAnime.third
            resolving = false
            return
        }

        resolving = true
        status = "Memuat ulang episode dari ${hit.provider}…"
        scope.launch {
            try {
                val candidates = if (allowFallback) listOf(hit) + hits.filter { it != hit } else listOf(hit)
                var chosen: ResolveHit? = null
                var detail: Map<String, Any?>? = null
                var episodeList = emptyList<Map<String, Any?>>()
                for (candidate in candidates) {
                    val loaded = withContext(Dispatchers.IO) {
                        CloudStreamRuntime.loadAnime(candidate.provider, candidate.item["url"]?.toString().orEmpty())
                    }
                    val loadedEpisodes = (loaded?.get("episodes") as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                    if (loadedEpisodes.isNotEmpty()) {
                        chosen = candidate
                        detail = loaded
                        episodeList = loadedEpisodes
                        break
                    }
                    if (requestId != sourceLoadRequestId) return@launch
                }
                if (requestId != sourceLoadRequestId) return@launch
                if (chosen != null) {
                    selected = chosen
                    anime = detail
                    episodes = episodeList
                    CloudStreamWatchCache.anime[mediaId] = Triple(chosen, detail!!, episodeList)
                    prefs.edit().putString("match_$mediaId", chosen.provider + "|" + chosen.item["url"].toString()).apply()
                } else {
                    error = if (allowFallback) {
                        "Provider yang cocok tidak menyediakan daftar episode."
                    } else {
                        "Provider ${hit.provider} tidak menyediakan daftar episode untuk judul ini."
                    }
                }
            } catch (t: Throwable) {
                if (requestId == sourceLoadRequestId) error = t.message ?: "Gagal memuat episode dari ${hit.provider}."
            } finally {
                if (requestId == sourceLoadRequestId) resolving = false
            }
        }
    }

    fun resolveTitle() {
        if (resolving) return
        resolving = true
        status = "Mencocokkan judul ke ${providers.size} provider…"
        error = null
        hits = emptyList()
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val gate = Semaphore(3)
                        providers.map { provider -> async {
                            gate.acquire()
                            try {
                                val name = provider["name"]?.toString().orEmpty()
                                val local = mutableListOf<ResolveHit>()
                                for (query in aliases) {
                                    CloudStreamRuntime.search(name, query).forEach { item ->
                                        val score = matchScore(item["name"]?.toString().orEmpty(), aliases)
                                        if (score >= 62) local += ResolveHit(name, item, score)
                                    }
                                    if (local.any { it.score >= 96 }) break
                                }
                                local.maxByOrNull { it.score }
                            } catch (_: Throwable) { null }
                            finally { gate.release() }
                        } }.awaitAll().filterNotNull()
                    }
                }
                val ranked = found.distinctBy { it.provider + "|" + it.item["url"] }
                    .sortedByDescending { it.score }
                hits = ranked
                if (ranked.isEmpty()) error = "Anime tidak ditemukan di provider yang terpasang."
                else {
                    val saved = prefs.getString("match_$mediaId", null)
                    val remembered = ranked.firstOrNull { it.provider + "|" + it.item["url"].toString() == saved }
                    loadHit(remembered ?: ranked.first())
                }
            } catch (t: Throwable) {
                error = t.message ?: "Auto Resolve gagal."
                resolving = false
            }
        }
    }

    LaunchedEffect(mediaId) {
        providers = withContext(Dispatchers.IO) {
            CloudStreamRuntime.providers().filter { p ->
                (p["types"] as? List<*>)?.any { it?.toString() in setOf("Anime", "AnimeMovie", "OVA") } == true
            }
        }
        if (providers.isEmpty()) error = "Belum ada provider anime. Tambahkan repository di Pengaturan → Provider."
        else if (CloudStreamWatchCache.anime[mediaId] != null) { val cached = CloudStreamWatchCache.anime[mediaId]!!; selected = cached.first; anime = cached.second; episodes = cached.third } else resolveTitle()
    }
    LaunchedEffect(malId) {
        if (malId != null && malId > 0) metadata = withContext(Dispatchers.IO) { loadAniZip(malId) }
    }

    fun sameServer(source: Map<String, Any?>, preferredName: String?): Boolean {
        if (preferredName.isNullOrBlank()) return false
        val candidate = source["name"]?.toString()?.trim().orEmpty()
        return candidate.equals(preferredName.trim(), ignoreCase = true)
    }

    // Provider episode arrays are not guaranteed to arrive in playback order. Use the
    // chronological episode number for Auto Next, matching the ascending list shown by default.
    fun nextEpisodeAfter(chosen: Map<String, Any?>): Map<String, Any?>? {
        val ordered = episodes.sortedWith(
            compareBy<Map<String, Any?>> { (it["episode"] as? Number)?.toInt() ?: Int.MAX_VALUE }
                .thenBy { it["name"]?.toString().orEmpty() }
        )
        val currentNumber = (chosen["episode"] as? Number)?.toInt()
        if (currentNumber != null) {
            return ordered.firstOrNull { item ->
                val number = (item["episode"] as? Number)?.toInt()
                number != null && number > currentNumber
            }
        }
        val currentIndex = ordered.indexOfFirst { it === chosen || it == chosen }
        return ordered.getOrNull(currentIndex + 1)
    }

    suspend fun resolvePreloadSource(
        chosen: Map<String, Any?>,
        preferredServerName: String? = selectedStream?.get("name")?.toString()
    ): Map<String, Any?>? {
        val hit = selected ?: return null
        val number = (chosen["episode"] as? Number)?.toInt()
        val detail = anime ?: return null
        val eps = (detail["episodes"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
        val match = eps.firstOrNull { item ->
            val n = (item["episode"] as? Number)?.toInt()
            if (number != null && n != null) n == number else item["name"]?.toString() == chosen["name"]?.toString()
        } ?: return null
        val data = match["data"]?.toString().orEmpty()
        if (data.isBlank()) return null
        return try {
            val first = withContext(Dispatchers.IO) { CloudStreamRuntime.resolve(hit.provider, data, true) }
            var links = (first["sources"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
            var done = first["done"] == true
            val started = System.currentTimeMillis()
            while (!done && System.currentTimeMillis() - started < 28_000L) {
                delay(650)
                val next = withContext(Dispatchers.IO) { CloudStreamRuntime.pollLinks(hit.provider, data) }
                links = (next["sources"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                done = next["done"] == true
                if (links.any { sameServer(it, preferredServerName) }) break
            }
            val playable = links.filter { !it["url"]?.toString().isNullOrBlank() }
            playable.firstOrNull { sameServer(it, preferredServerName) } ?: playable.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    // A preload belongs to one exact episode + selected server. Invalidate older requests
    // immediately when the user changes server so a late result from the previous server can
    // never be consumed by Auto Next.
    fun scheduleNextPreload(nextEpisode: Map<String, Any?>, source: Map<String, Any?>) {
        val requestId = ++preloadRequestId
        val activeEpisodeKey = episode?.get("data")?.toString()
            ?: episode?.get("episode")?.toString()
        val sourceName = source["name"]?.toString()
        preloadStream = null
        scope.launch {
            val resolved = resolvePreloadSource(nextEpisode, sourceName)
            val currentEpisodeKey = episode?.get("data")?.toString()
                ?: episode?.get("episode")?.toString()
            if (requestId == preloadRequestId &&
                selectedStream == source &&
                currentEpisodeKey == activeEpisodeKey
            ) {
                preloadStream = resolved
            }
        }
    }

    fun playEpisode(
        chosen: Map<String, Any?>,
        preferredServerName: String? = null,
        preloadedSource: Map<String, Any?>? = null
    ) {
        preloadRequestId++
        preloadStream = null
        episode = chosen
        sheet = true
        error = null

        // Auto Next already resolved this source in the background. Reuse it immediately and
        // keep the existing episode panel/player composed instead of showing the first-open loader.
        if (preloadedSource != null && !preloadedSource["url"]?.toString().isNullOrBlank()) {
            val previousStreams = streams
            streams = (previousStreams.filterNot { sameServer(it, preloadedSource["name"]?.toString()) } + preloadedSource)
            selectedStream = preloadedSource
            subtitles = emptyList()
            preloadStream = null
            resolving = false
            status = "Memutar episode berikutnya…"
            // The next episode is resolved only after the current episode's buffer is full.
            return
        }

        streams = emptyList()
        subtitles = emptyList()
        selectedStream = null
        preloadStream = null
        resolving = true
        status = "Mencari sumber video…"
        scope.launch {
            val number = (chosen["episode"] as? Number)?.toInt()
            val activeHit = selected
            val cacheKey = "${activeHit?.provider}:${activeHit?.item?.get("url")}:$mediaId:${number ?: chosen["data"] ?: chosen["name"]}"
            val cachedStreams = CloudStreamWatchCache.streams[cacheKey]
            if (cachedStreams != null) {
                streams = cachedStreams.first
                subtitles = cachedStreams.second
                selectedStream = cachedStreams.first.firstOrNull { sameServer(it, preferredServerName) }
                    ?: cachedStreams.first.firstOrNull()
                resolving = false
                return@launch
            }
            // Episodes were loaded from the selected provider; never resolve their data
            // against another provider, or the player can silently mix episode sources.
            val candidates = listOfNotNull(selected)
            var success = false
            for (hit in candidates) {
                try {
                    val detail = if (hit == selected) anime else withContext(Dispatchers.IO) {
                        CloudStreamRuntime.loadAnime(hit.provider, hit.item["url"]?.toString().orEmpty())
                    }
                    val eps = (detail?.get("episodes") as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                    val match = eps.firstOrNull { e ->
                        val n = (e["episode"] as? Number)?.toInt()
                        if (number != null && n != null) n == number else e["name"]?.toString() == chosen["name"]?.toString()
                    } ?: continue
                    val data = match["data"]?.toString().orEmpty()
                    if (data.isBlank()) continue
                    val first = withContext(Dispatchers.IO) { CloudStreamRuntime.resolve(hit.provider, data, true) }
                    var links = (first["sources"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                    var subs = (first["subtitles"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                    var done = first["done"] == true
                    val started = System.currentTimeMillis()
                    while (!done && System.currentTimeMillis() - started < 28000L) {
                        delay(650)
                        val next = withContext(Dispatchers.IO) { CloudStreamRuntime.pollLinks(hit.provider, data) }
                        links = (next["sources"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                        subs = (next["subtitles"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                        done = next["done"] == true
                        if (links.isNotEmpty() &&
                            (preferredServerName.isNullOrBlank() || links.any { sameServer(it, preferredServerName) })
                        ) {
                            streams = links; subtitles = subs
                            selectedStream = links.firstOrNull { sameServer(it, preferredServerName) } ?: links.first(); selected = hit
                            CloudStreamWatchCache.streams[cacheKey] = links to subs
                            success = true
                            break
                        }
                    }
                    if (success) break
                    if (links.isNotEmpty()) {
                        streams = links; subtitles = subs
                        selectedStream = links.firstOrNull { sameServer(it, preferredServerName) } ?: links.first(); selected = hit
                        CloudStreamWatchCache.streams[cacheKey] = links to subs
                        success = true
                        break
                    }
                } catch (_: Throwable) { /* Try the next matching provider. */ }
            }
            if (!success) error = "Tidak ada provider yang berhasil menyediakan video untuk episode ini."
            resolving = false
            // Do not resolve the next episode here; wait for the current video buffer to reach its end.
        }
    }

    fun searchManually(providerName: String? = null) {
        val query = manualQuery.trim()
        if (query.isBlank() || resolving) return
        resolving = true
        error = null
        status = if (providerName == null) "Mencari \"$query\" di semua provider…" else "Mencari \"$query\" di $providerName…"
        scope.launch {
            try {
                val targetProviders = providers.filter { providerName == null || it["name"]?.toString() == providerName }
                val found = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val gate = Semaphore(3)
                        targetProviders.map { provider -> async {
                            gate.acquire()
                            try {
                                val name = provider["name"]?.toString().orEmpty()
                                CloudStreamRuntime.search(name, query).mapNotNull { item ->
                                    val score = matchScore(item["name"]?.toString().orEmpty(), listOf(query))
                                    if (score > 0) ResolveHit(name, item, score) else null
                                }
                            } catch (_: Throwable) { emptyList() }
                            finally { gate.release() }
                        } }.awaitAll().flatten()
                    }
                }
                hits = found.distinctBy { it.provider + "|" + it.item["url"] }.sortedByDescending { it.score }
                if (hits.isEmpty()) error = "Tidak ada hasil untuk \"$query\"" + (providerName?.let { " di $it" } ?: "") + ". Coba judul lain."
                else status = "${hits.size} hasil ditemukan. Pilih salah satu secara manual."
            } catch (t: Throwable) { error = t.message ?: "Pencarian manual gagal." }
            finally { resolving = false }
        }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Auto Resolve", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(selected?.let { "Sumber: ${it.provider} · relevansi ${it.score}%" } ?: "Pencocokan otomatis judul anime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { correction = true }, enabled = providers.isNotEmpty()) { Text("Judul salah/sumber") }
        }
        if (resolving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (anime != null && episodes.isNotEmpty()) {
            var newestFirst by rememberSaveable(mediaId) { mutableStateOf(false) }
            val displayedEpisodes = remember(episodes, newestFirst) {
                if (newestFirst) episodes.sortedByDescending { (it["episode"] as? Number)?.toInt() ?: Int.MIN_VALUE }
                else episodes.sortedBy { (it["episode"] as? Number)?.toInt() ?: Int.MAX_VALUE }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${episodes.size} episode", style = MaterialTheme.typography.titleMedium)
                Surface(
                    onClick = { newestFirst = !newestFirst },
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sort,
                        contentDescription = if (newestFirst) "Urutkan episode paling lama" else "Urutkan episode paling baru",
                        modifier = Modifier.padding(11.dp).size(20.dp)
                    )
                }
            }
            // Render episodes as one continuous list inside the detail page. The previous nested
            // fixed-height LazyColumn and optional two-column bundles trapped scroll gestures in
            // a second viewport, making it hard to move through the whole anime page.
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayedEpisodes.forEach { item ->
                    val n = (item["episode"] as? Number)?.toInt()
                    val meta = n?.let { metadata[it] }
                    val title = meta?.title?.takeIf(String::isNotBlank)
                        ?: item["name"]?.toString()?.takeIf(String::isNotBlank)
                        ?: "Episode ${n ?: "?"}"
                    val isWatched = n != null && trackedProgress != null && n <= trackedProgress
                    Card(
                        onClick = { playEpisode(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = if (isWatched) 0.48f else 1f },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = meta?.image ?: item["posterUrl"]?.toString(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(112.dp, 66.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "Episode ${n ?: "?"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatEpisodeReleaseDate(meta?.releaseDate),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
                                )
                            }
                            if (isWatched) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "Ditonton",
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (correction) AlertDialog(
        onDismissRequest = { correction = false },
        title = { Text("Judul salah/sumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = manualQuery, onValueChange = { manualQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Cari judul anime") }, placeholder = { Text("Masukkan judul yang ingin dicari") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { searchManually() }, enabled = manualQuery.isNotBlank() && !resolving, modifier = Modifier.weight(1f)) { Text("Cari semua provider") }
                    OutlinedButton(onClick = { correction = false; resolveTitle() }, enabled = !resolving) { Text("Auto Resolve") }
                }
                Text("Atau cari hanya di provider tertentu:", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.heightIn(max = 150.dp)) {
                    items(providers, key = { it["name"]?.toString().orEmpty() }) { provider ->
                        val name = provider["name"]?.toString().orEmpty()
                        TextButton(onClick = { searchManually(name) }, enabled = manualQuery.isNotBlank() && !resolving, modifier = Modifier.fillMaxWidth()) { Text("Cari di $name", modifier = Modifier.fillMaxWidth()) }
                    }
                }
                if (resolving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(status, style = MaterialTheme.typography.bodySmall) }
                if (hits.isNotEmpty()) {
                    Text("Hasil pencarian — pilih judul yang benar", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(hits, key = { it.provider + "|" + it.item["url"] }) { hit ->
                            TextButton(onClick = { correction = false; loadHit(hit, allowFallback = false) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(hit.item["name"]?.toString() ?: manualQuery, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${hit.provider} · ${hit.score}% kecocokan", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { correction = false }) { Text("Tutup") }
            }
        },
        confirmButton = { TextButton(onClick = { correction = false }) { Text("Tutup") } }
    )

    if (sheet && episode != null) {
        val current = episode!!
        val number = (current["episode"] as? Number)?.toInt()
        val meta = number?.let { metadata[it] }
        LaunchedEffect(number, sheet) {
            FullscreenVideoBridge.episodeLabel.value = if (sheet) number?.let { "Episode $it" } else null
        }
        LaunchedEffect(sheet, number, selectedStream != null) {
            val showForThisEpisode = sheet && number != null && selectedStream != null
            episodePopupVisible = showForThisEpisode
            if (showForThisEpisode) {
                delay(10_000L)
                episodePopupVisible = false
            }
        }
        ThemeSheet(onDismiss = { sheet = false; episode = null; FullscreenVideoBridge.episodeLabel.value = null }) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (selectedStream != null) {
                    val stream = selectedStream!!
                    val url = stream["url"]?.toString()
                    if (!url.isNullOrBlank()) {
                        Box(Modifier.fillMaxWidth()) {
                        VideoPlayer(
                            url = url,
                            headers = (stream["headers"] as? Map<*, *>)?.entries?.associate {
                                it.key.toString() to it.value.toString()
                            }.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            playerCache = null,
                            startMuted = false,
                            loop = false,
                            autoPlay = true,
                            useWsolaAudio = true,
                            onAutoNextChange = { autoNextEnabled = it },
                            autoEnterFullscreen = autoNextFullscreen,
                            onFullscreenEntered = { autoNextFullscreen = false },
                            preloadUrl = preloadStream?.get("url")?.toString(),
                            preloadHeaders = (preloadStream?.get("headers") as? Map<*, *>)?.entries?.associate {
                                it.key.toString() to it.value.toString()
                            }.orEmpty(),
                            onBufferComplete = {
                                if (autoNextEnabled) {
                                    val nextEpisode = nextEpisodeAfter(current)
                                    val source = selectedStream
                                    if (nextEpisode != null && source != null && preloadStream == null) {
                                        scheduleNextPreload(nextEpisode, source)
                                    }
                                }
                            },
                            aniskipMalId = malId,
                            aniskipEpisodeNumber = number,
                            onPlaybackEnded = {
                                if (autoNextEnabled) {
                                    val wasFullscreen = FullscreenVideoBridge.isFullscreen
                                    val preferredServerName = selectedStream?.get("name")?.toString()
                                    val readyNextSource = preloadStream?.takeIf {
                                        !preferredServerName.isNullOrBlank() &&
                                            it["name"]?.toString().equals(preferredServerName, ignoreCase = true)
                                    }
                                    // Reuse the fullscreen Activity when the preloaded source is ready.
                                    // Otherwise close/re-enter only as a fallback while resolving.
                                    autoNextFullscreen = wasFullscreen && readyNextSource == null
                                    val next = nextEpisodeAfter(current)
                                    if (next != null) {
                                        playEpisode(
                                            next,
                                            selectedStream?.get("name")?.toString(),
                                            readyNextSource
                                        )
                                        if (wasFullscreen && readyNextSource == null) {
                                            FullscreenVideoBridge.closeFullscreen?.invoke()
                                        }
                                    }
                                }
                            }
                        )
                        if (episodePopupVisible) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                tonalElevation = 6.dp
                            ) {
                                Text(
                                    "Episode ${number ?: "?"}",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        }
                    }
                } else if (resolving) {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                ThemeSheetIdentity(
                    badge = "Episode ${number ?: "?"}",
                    title = mediaTitle,
                    subtitle = meta?.title ?: current["name"]?.toString()
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetLabel("Deskripsi")
                    Text(
                        meta?.summary?.takeIf(String::isNotBlank)
                            ?: "Deskripsi belum tersedia dari AniZip.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetLabel("Server")
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        streams.forEach { source ->
                            val name = source["name"]?.toString() ?: "Server"
                            val quality = source["quality"]?.toString()
                            SourceChip(
                                label = listOfNotNull(name, quality).joinToString(" · "),
                                isSelected = selectedStream === source,
                                onClick = {
                                    // Changing server invalidates any pending preload. The new
                                    // server's next episode will be prepared only at buffer completion.
                                    preloadRequestId++
                                    preloadStream = null
                                    selectedStream = source
                                }
                            )
                        }
                    }
                    if (streams.isEmpty() && !resolving) {
                        Text(
                            "Belum ada server yang tersedia.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (subtitles.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetLabel("Subtitle")
                        Text(
                            subtitles.mapNotNull { it["lang"]?.toString() }.distinct().joinToString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    "Data judul dari AniList, metadata episode dari AniZip, dan video dari provider CloudStream.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun normalizeTitle(value: String): String =
    Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

private fun matchScore(candidate: String, aliases: List<String>): Int {
    val title = normalizeTitle(candidate)
    var best = 0
    aliases.forEach { alias ->
        val target = normalizeTitle(alias)
        if (title.isBlank() || target.isBlank()) return@forEach
        val score = when {
            title == target -> 100
            title.startsWith(target) || target.startsWith(title) -> 92
            title.contains(target) || target.contains(title) -> 86
            else -> {
                val a = title.split(" ").filter { it.length > 1 }.toSet()
                val b = target.split(" ").filter { it.length > 1 }.toSet()
                if (a.isEmpty() || b.isEmpty()) 0 else ((a intersect b).size.toDouble() / maxOf(a.size, b.size) * 82).toInt()
            }
        }
        best = maxOf(best, score)
    }
    return best
}

private fun loadAniZip(malId: Int): Map<Int, AniZipEpisode> {
    val connection = URL("https://api.ani.zip/mappings?mal_id=$malId").openConnection() as HttpURLConnection
    connection.connectTimeout = 6500
    connection.readTimeout = 6500
    return try {
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        val all = JSONObject(json).optJSONObject("episodes") ?: return emptyMap()
        buildMap {
            val keys = all.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val number = key.toIntOrNull() ?: continue
                val row = all.optJSONObject(key) ?: continue
                val rawTitle = row.opt("title")
                val title = if (rawTitle is JSONObject) rawTitle.optString("en").takeIf { it.isNotBlank() && it != "null" }
                    ?: rawTitle.optString("x-jat").takeIf { it.isNotBlank() && it != "null" }
                    else (rawTitle as? String)?.takeIf { it.isNotBlank() && it != "null" }
                val summary = row.optString("summary").takeIf { it.isNotBlank() && it != "null" }
                    ?: row.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                val image = row.optString("image").takeIf { it.startsWith("http") }
                val releaseDate = row.optString("airdate").takeIf { it.isNotBlank() && it != "null" } ?: row.optString("airingAt").takeIf { it.isNotBlank() && it != "null" }
                put(number, AniZipEpisode(title, summary, image, releaseDate))
            }
        }
    } catch (_: Throwable) { emptyMap() }
    finally { connection.disconnect() }
}

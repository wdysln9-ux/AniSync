package com.anisync.android.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anisync.android.cloudstream.CloudStreamRuntime
import com.anisync.android.presentation.components.VideoPlayer
import com.anisync.android.presentation.details.components.ThemeSheet
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
import java.util.Locale

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("anisync_auto_resolve", 0) }
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
    var resolving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Menyiapkan Auto Resolve…") }
    var error by remember { mutableStateOf<String?>(null) }
    var correction by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var autoNextEnabled by remember { mutableStateOf(true) }
    var autoNextFullscreen by remember { mutableStateOf(false) }
    var manualQuery by remember(mediaTitle) { mutableStateOf(mediaTitle) }

    fun loadHit(hit: ResolveHit) {
        selected = hit
        val cachedAnime = CloudStreamWatchCache.anime[mediaId]
        if (cachedAnime != null && cachedAnime.first.provider == hit.provider && cachedAnime.first.item["url"] == hit.item["url"]) { anime = cachedAnime.second; episodes = cachedAnime.third; resolving = false; error = null; return }
        anime = null
        episodes = emptyList()
        resolving = true
        status = "Memuat episode dari ${hit.provider}…"
        scope.launch {
            try {
                val candidates = listOf(hit) + hits.filter { it != hit }
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
                }
                if (chosen != null) {
                    selected = chosen
                    anime = detail
                    episodes = episodeList
                    CloudStreamWatchCache.anime[mediaId] = Triple(chosen, detail!!, episodeList)
                    prefs.edit().putString("match_$mediaId", chosen.provider + "|" + chosen.item["url"].toString()).apply()
                } else {
                    error = "Anime ditemukan, tetapi provider yang cocok tidak menyediakan daftar episode."
                }
            } catch (t: Throwable) {
                error = t.message ?: "Gagal memuat episode."
            } finally { resolving = false }
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

    fun playEpisode(chosen: Map<String, Any?>) {
        episode = chosen
        streams = emptyList()
        subtitles = emptyList()
        selectedStream = null
        sheet = true
        resolving = true
        status = "Mencari sumber video…"
        scope.launch {
            val number = (chosen["episode"] as? Number)?.toInt()
            val cacheKey = "$mediaId:${number ?: chosen["data"] ?: chosen["name"]}"
            val cachedStreams = CloudStreamWatchCache.streams[cacheKey]
            if (cachedStreams != null) { streams = cachedStreams.first; subtitles = cachedStreams.second; selectedStream = cachedStreams.first.firstOrNull(); resolving = false; return@launch }
            val candidates = listOfNotNull(selected) + hits.filter { it != selected }
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
                        if (links.isNotEmpty()) {
                            streams = links; subtitles = subs; selectedStream = links.first(); selected = hit
                            CloudStreamWatchCache.streams[cacheKey] = links to subs
                            success = true
                            break
                        }
                    }
                    if (success) break
                    if (links.isNotEmpty()) {
                        streams = links; subtitles = subs; selectedStream = links.first(); selected = hit
                        CloudStreamWatchCache.streams[cacheKey] = links to subs
                        success = true
                        break
                    }
                } catch (_: Throwable) { /* Try the next matching provider. */ }
            }
            if (!success) error = "Tidak ada provider yang berhasil menyediakan video untuk episode ini."
            resolving = false
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
            Text("${episodes.size} episode", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().height(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(episodes, key = { (it["data"] ?: it["episode"] ?: it.hashCode()).toString() }) { item ->
                    val n = (item["episode"] as? Number)?.toInt()
                    val meta = n?.let { metadata[it] }
                    val title = meta?.title?.takeIf(String::isNotBlank) ?: item["name"]?.toString()?.takeIf(String::isNotBlank) ?: "Episode ${n ?: "?"}"
                    Card(onClick = { playEpisode(item) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(model = meta?.image ?: item["posterUrl"]?.toString(), contentDescription = null, modifier = Modifier.size(112.dp, 66.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${n ?: "?"}. ${title}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(meta?.releaseDate ?: "Tanggal rilis belum tersedia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f))
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
                            TextButton(onClick = { correction = false; loadHit(hit) }, modifier = Modifier.fillMaxWidth()) {
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
        ThemeSheet(onDismiss = { sheet = false; episode = null }) {
            Text("Episode ${number ?: "?"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(mediaTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(meta?.title ?: current["name"]?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium)
            if (selectedStream != null) {
                val stream = selectedStream!!
                val url = stream["url"]?.toString()
                if (!url.isNullOrBlank()) VideoPlayer(url, headers = (stream["headers"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }.orEmpty(), modifier = Modifier.fillMaxWidth(), playerCache = null, startMuted = false, loop = false, autoPlay = true, sourceOptions = streams.map { source -> listOfNotNull(source["name"]?.toString(), source["quality"]?.toString()).joinToString(" · ").ifBlank { "Server" } }, onSelectSource = { index -> selectedStream = streams.getOrNull(index) }, onAutoNextChange = { autoNextEnabled = it }, autoEnterFullscreen = autoNextFullscreen, onFullscreenEntered = { autoNextFullscreen = false }, onPlaybackEnded = { if (autoNextEnabled) { autoNextFullscreen = false; val index = episodes.indexOfFirst { it === current || it == current }; episodes.getOrNull(index + 1)?.let { next -> autoNextFullscreen = true; playEpisode(next) } } })
            } else if (resolving) Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Text("Ringkasan episode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(meta?.summary?.takeIf(String::isNotBlank) ?: "Ringkasan episode belum tersedia dari AniZip.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (streams.isEmpty() && !resolving) Text("Belum ada sumber video yang tersedia.", style = MaterialTheme.typography.bodySmall)
            streams.forEach { source ->
                val name = source["name"]?.toString() ?: "Server"
                val quality = source["quality"]?.toString()
                TextButton(onClick = { selectedStream = source }) { Text("${if (selectedStream === source) "✓ " else ""}${name}${if (!quality.isNullOrBlank()) " · $quality" else ""}") }
            }
            if (subtitles.isNotEmpty()) Text("Subtitle tersedia: " + subtitles.mapNotNull { it["lang"]?.toString() }.distinct().joinToString(), style = MaterialTheme.typography.bodySmall)
            Text("Data judul dari AniList, metadata episode dari AniZip, dan video dari provider CloudStream.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

package com.anisync.android.presentation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.anisync.android.cloudstream.CloudStreamRuntime
import com.anisync.android.presentation.components.VideoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudStreamWatchContent(
    mediaTitle: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var query by remember(mediaTitle) { mutableStateOf(mediaTitle) }
    var results by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var selectedAnime by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var episodes by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var selectedStream by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var streams by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var subtitles by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun search(source: String, term: String) {
        scope.launch {
            loading = true
            message = null
            selectedAnime = null
            episodes = emptyList()
            selectedStream = null
            streams = emptyList()
            try {
                results = withContext(Dispatchers.IO) { CloudStreamRuntime.search(source, term) }
                if (results.isEmpty()) message = "Tidak ada hasil dari provider ini."
            } catch (t: Throwable) {
                message = t.message ?: "Pencarian gagal."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(mediaTitle) {
        loading = true
        try {
            val loadedProviders = withContext(Dispatchers.IO) {
                CloudStreamRuntime.providers().filter { provider ->
                    val types = provider["types"] as? List<*>
                    types?.any { type -> type?.toString()?.let { it in setOf("Anime", "AnimeMovie", "OVA") } == true } == true
                }
            }
            providers = loadedProviders
            val first = loadedProviders.firstOrNull()?.get("name")?.toString()
            selectedProvider = first
            if (first == null) {
                message = "Belum ada provider anime CloudStream yang terpasang. Tambahkan repository di Pengaturan → Provider."
            } else {
                val found = withContext(Dispatchers.IO) { CloudStreamRuntime.search(first, mediaTitle) }
                results = found
                if (found.isEmpty()) message = "Anime tidak ditemukan. Coba provider lain atau ubah kata pencarian."
            }
        } catch (t: Throwable) {
            message = t.message ?: "Gagal memuat provider."
        } finally {
            loading = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Tonton menggunakan extension CloudStream", style = MaterialTheme.typography.titleMedium)
        if (providers.isNotEmpty()) {
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers, key = { it["name"].toString() }) { provider ->
                    val name = provider["name"]?.toString().orEmpty()
                    TextButton(
                        onClick = {
                            selectedProvider = name
                            search(name, query)
                        }
                    ) { Text(if (selectedProvider == name) "✓ $name" else name) }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Cari judul anime") },
            singleLine = true
        )
        Button(
            onClick = { selectedProvider?.let { search(it, query) } },
            enabled = selectedProvider != null && query.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Cari anime") }

        if (loading) CircularProgressIndicator()
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }

        if (selectedAnime == null && results.isNotEmpty()) {
            Text("Hasil pencarian", style = MaterialTheme.typography.titleSmall)
            results.forEach { result ->
                Card(
                    onClick = {
                        val source = result["apiName"]?.toString() ?: selectedProvider
                        val url = result["url"]?.toString()
                        if (source != null && !url.isNullOrBlank()) {
                            scope.launch {
                                loading = true
                                message = null
                                try {
                                    val detail = withContext(Dispatchers.IO) { CloudStreamRuntime.loadAnime(source, url) }
                                    selectedAnime = detail
                                    episodes = (detail?.get("episodes") as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                                    if (episodes.isEmpty()) message = "Provider tidak mengembalikan daftar episode."
                                } catch (t: Throwable) {
                                    message = t.message ?: "Gagal memuat episode."
                                } finally { loading = false }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(result["name"]?.toString() ?: "Tanpa judul", style = MaterialTheme.typography.titleSmall)
                        val quality = result["quality"]?.toString()
                        val lang = result["dubStatus"]?.toString()
                        Text(listOfNotNull(quality, lang).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        selectedAnime?.let { anime ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(anime["name"]?.toString() ?: "Anime", style = MaterialTheme.typography.titleMedium)
                    Text("${episodes.size} episode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = {
                    selectedAnime = null
                    episodes = emptyList()
                    selectedStream = null
                }) { Text("Ganti anime") }
            }
            episodes.forEachIndexed { index, episode ->
                Card(
                    onClick = {
                        val source = anime["apiName"]?.toString() ?: selectedProvider
                        val data = episode["data"]?.toString()
                        if (source != null && !data.isNullOrBlank()) {
                            scope.launch {
                                loading = true
                                message = "Mencari sumber video…"
                                selectedStream = null
                                streams = emptyList()
                                try {
                                    val resolved = withContext(Dispatchers.IO) { CloudStreamRuntime.resolve(source, data, true) }
                                    streams = (resolved["sources"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                                    subtitles = (resolved["subtitles"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
                                    selectedStream = streams.firstOrNull()
                                    message = if (streams.isEmpty()) "Tidak ada sumber video yang berhasil di-resolve." else null
                                } catch (t: Throwable) {
                                    message = t.message ?: "Resolving gagal."
                                } finally { loading = false }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            episode["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "Episode ${episode["episode"] ?: index + 1}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text("Ketuk untuk Auto Resolve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (streams.isNotEmpty()) {
            Text("Sumber video", style = MaterialTheme.typography.titleSmall)
            streams.forEach { stream ->
                val streamName = stream["name"]?.toString() ?: "Server"
                val quality = stream["quality"]?.toString()
                TextButton(onClick = { selectedStream = stream }) {
                    Text(if (selectedStream === stream) "✓ $streamName" else streamName)
                    if (!quality.isNullOrBlank()) Text(" • $quality")
                }
            }
            selectedStream?.let { stream ->
                val url = stream["url"]?.toString()
                if (!url.isNullOrBlank()) {
                    VideoPlayer(
                        url = url,
                        headers = (stream["headers"] as? Map<*, *>)?.entries
                            ?.associate { it.key.toString() to it.value.toString() }.orEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        playerCache = null,
                        startMuted = false,
                        loop = false,
                        autoPlay = true
                    )
                }
            }
            if (subtitles.isNotEmpty()) {
                Text(
                    "Subtitle tersedia: " + subtitles.mapNotNull { it["lang"]?.toString() }.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

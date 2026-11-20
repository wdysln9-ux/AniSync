package com.anisync.android.cloudstream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class CloudStreamPlugin(
    val internalName: String,
    val name: String,
    val url: String,
    val version: Int,
    val language: String?,
    val types: List<String>,
    val iconUrl: String?,
    val repoUrl: String,
) {
    val installId: String get() = "${internalName}@${version}"
}

data class CloudStreamRepository(
    val name: String,
    val url: String,
    val plugins: List<CloudStreamPlugin>,
)

/**
 * Reads the official CloudStream repo.json/pluginLists format and stores downloaded .cs3 files
 * in app-private storage. Repository URLs and plugin metadata are treated as untrusted input.
 */
class CloudStreamRepositoryManager(context: Context) {
    private val hostContext = context
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("cloudstream_repositories", Context.MODE_PRIVATE)
    private val extensionDir = File(appContext.filesDir, "cloudstream/extensions").apply { mkdirs() }

    fun repositoryUrls(): List<String> =
        preferences.getStringSet(KEY_REPOSITORIES, emptySet()).orEmpty().toList().sorted()

    fun installedIds(): Set<String> =
        extensionDir.listFiles().orEmpty().filter { it.isFile && it.extension == "cs3" }
            .map { it.nameWithoutExtension.substringBeforeLast("@repo-", it.nameWithoutExtension) }.toSet()

    suspend fun addRepository(rawUrl: String): CloudStreamRepository = withContext(Dispatchers.IO) {
        val url = validateRepositoryUrl(rawUrl)
        val repository = fetchRepository(url)
        val urls = repositoryUrls().toMutableSet().apply { add(url) }
        preferences.edit().putStringSet(KEY_REPOSITORIES, urls).apply()
        repository
    }

    suspend fun refreshRepository(rawUrl: String): CloudStreamRepository = withContext(Dispatchers.IO) {
        fetchRepository(validateRepositoryUrl(rawUrl))
    }

    suspend fun allRepositories(): List<CloudStreamRepository> = withContext(Dispatchers.IO) {
        repositoryUrls().mapNotNull { url -> runCatching { fetchRepository(url) }.getOrNull() }
    }

    fun removeRepository(url: String) {
        preferences.edit().putStringSet(KEY_REPOSITORIES, repositoryUrls().filterNot { it == url }.toSet()).apply()
    }

    suspend fun install(plugin: CloudStreamPlugin): File = withContext(Dispatchers.IO) {
        val safeName = plugin.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.isNotBlank()) { "Nama extension tidak valid" }
        val repoTag = plugin.repoUrl.hashCode().toUInt().toString(16)
        val destination = File(extensionDir, "${safeName}@${plugin.version}@repo-$repoTag.cs3")
        if (destination.exists() && destination.length() > 0L) return@withContext destination
        val connection = (URL(plugin.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            require(connection.responseCode in 200..299) { "Download extension gagal: HTTP ${connection.responseCode}" }
            val temporary = File(extensionDir, destination.name + ".part")
            connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            require(temporary.length() > 0L) { "File extension kosong" }
            check(temporary.renameTo(destination)) { "Tidak bisa menyimpan extension" }
            val loaded = CloudStreamRuntime.load(destination, hostContext)
            if (!loaded) {
                destination.setWritable(true)
                destination.delete()
                error("Extension terunduh, tetapi gagal dimuat oleh CloudStream.")
            }
            destination
        } finally {
            connection.disconnect()
        }
    }

    fun uninstall(plugin: CloudStreamPlugin) {
        val safeName = plugin.internalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val repoTag = plugin.repoUrl.hashCode().toUInt().toString(16)
        CloudStreamRuntime.uninstall(File(extensionDir, "${safeName}@${plugin.version}@repo-$repoTag.cs3"))
    }

    private fun fetchRepository(repoUrl: String): CloudStreamRepository {
        val root = JSONObject(downloadText(repoUrl))
        val name = root.optString("name").takeIf { it.isNotBlank() } ?: URL(repoUrl).host
        val lists = root.optJSONArray("pluginLists") ?: JSONArray()
        val plugins = buildList {
            for (index in 0 until lists.length()) {
                val listUrl = resolveUrl(repoUrl, lists.optString(index))
                runCatching {
                    val array = JSONArray(downloadText(listUrl))
                    for (itemIndex in 0 until array.length()) {
                        val item = array.optJSONObject(itemIndex) ?: continue
                        val pluginUrl = resolveUrl(listUrl, item.optString("url"))
                        if (pluginUrl.isBlank()) continue
                        add(
                            CloudStreamPlugin(
                                internalName = item.optString("internalName").ifBlank { item.optString("name") },
                                name = item.optString("name").ifBlank { item.optString("internalName") },
                                url = pluginUrl,
                                version = item.optInt("version", 1),
                                language = item.optString("language").takeIf { it.isNotBlank() },
                                types = item.optJSONArray("tvTypes")?.toStringList().orEmpty(),
                                iconUrl = item.optString("iconUrl").takeIf { it.startsWith("https://") },
                                repoUrl = repoUrl,
                            )
                        )
                    }
                }
            }
        }.distinctBy { "${it.internalName}|${it.version}|${it.repoUrl}" }
        CloudStreamRepository(name, repoUrl, plugins)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun downloadText(rawUrl: String): String {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            require(connection.responseCode in 200..299) { "Repository tidak bisa diakses: HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateRepositoryUrl(raw: String): String {
        val uri = URL(raw.trim())
        require(uri.protocol == "https") { "Repository harus menggunakan HTTPS" }
        require(uri.host.isNotBlank()) { "URL repository tidak valid" }
        return uri.toString()
    }

    private fun resolveUrl(base: String, candidate: String): String =
        runCatching { URL(URL(base), candidate).toString() }.getOrDefault("")

    companion object {
        private const val KEY_REPOSITORIES = "urls"
        private const val USER_AGENT = "AniSync-CloudStream/1.0"
    }
}

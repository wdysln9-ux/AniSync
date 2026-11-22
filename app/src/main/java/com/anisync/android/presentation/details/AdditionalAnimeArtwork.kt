package com.anisync.android.presentation.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort extra artwork lookup matching AnimeW's gallery sources.
 * Each provider is isolated so a missing AniZip mapping never blocks TMDB
 * artwork (or the original AniList cover/banner).
 */
internal suspend fun loadAdditionalAnimeArtwork(
    anilistId: Int,
    malId: Int?
): List<String> = withContext(Dispatchers.IO) {
    val urls = linkedSetOf<String>()

    runCatching {
        val response = getJson("https://api.ani.zip/v1/episodes?anilist_id=$anilistId")
        val images = response.optJSONArray("images")
            ?: response.optJSONObject("data")?.optJSONArray("images")
        val allowedTypes = setOf("Poster", "Banner", "Fanart", "Clearlogo")
        if (images != null) {
            for (index in 0 until images.length()) {
                val image = images.optJSONObject(index) ?: continue
                val type = image.optString("coverType")
                val imageUrl = image.optString("url").trim()
                if (type in allowedTypes && imageUrl.startsWith("https://")) {
                    urls += imageUrl
                }
            }
        }
    }

    runCatching {
        val mappingUrl = if (malId != null && malId > 0) {
            "https://api.ani.zip/mappings?mal_id=$malId"
        } else {
            "https://api.ani.zip/mappings?anilist_id=$anilistId"
        }
        val mapping = getJson(mappingUrl)
        val tmdbId = mapping.optJSONObject("mappings")
            ?.optString("themoviedb_id")
            ?.toIntOrNull()
        if (tmdbId != null && tmdbId > 0) {
            val patterns = listOf(
                Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE)
            )
            // AniZip mappings may point to either a TV series or an anime film.
            for (kind in listOf("tv", "movie")) {
                val html = runCatching { getText("https://www.themoviedb.org/$kind/$tmdbId") }.getOrNull()
                    ?: continue
                val poster = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
                    ?.replace("&amp;", "&")
                    ?.trim()
                if (poster != null && poster.startsWith("https://image.tmdb.org/")) {
                    urls += poster
                    break
                }
            }
        }
    }

    urls.toList()
}

private fun getJson(url: String): JSONObject = JSONObject(getText(url))

private fun getText(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,text/html,*/*")
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        )
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

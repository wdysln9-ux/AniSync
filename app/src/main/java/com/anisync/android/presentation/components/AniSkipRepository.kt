package com.anisync.android.presentation.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

internal data class AniSkipSegment(
    val startMs: Long,
    val endMs: Long,
    val label: String
)

/** Reads OP/ED timestamps from AniSkip. Times from the API are seconds. */
internal object AniSkipRepository {
    suspend fun getSegments(malId: Int, episodeNumber: Int, durationMs: Long): List<AniSkipSegment> =
        withContext(Dispatchers.IO) {
            if (malId <= 0 || episodeNumber <= 0) return@withContext emptyList()
            val durationSeconds = (durationMs.coerceAtLeast(0L) / 1000.0)
            val accurate = request(malId, episodeNumber, durationSeconds)
            val results = if (accurate.isNotEmpty()) accurate else request(malId, episodeNumber, 0.0)
            if (results.isEmpty()) return@withContext emptyList()

            val candidates = results.mapNotNull { item ->
                val type = item.optString("skipType")
                val label = when (type) {
                    "op", "mixed-op" -> "Opening"
                    "ed", "mixed-ed" -> "Ending"
                    else -> return@mapNotNull null
                }
                val interval = item.optJSONObject("interval") ?: return@mapNotNull null
                val start = interval.optDouble("startTime", Double.NaN)
                val end = interval.optDouble("endTime", Double.NaN)
                val sourceLength = item.optDouble("episodeLength", 0.0)
                if (!start.isFinite() || !end.isFinite() || end <= start) return@mapNotNull null
                val offset = if (durationSeconds > 0.0 && sourceLength > 0.0) durationSeconds - sourceLength else 0.0
                val startMs = ((start + offset) * 1000.0).toLong().coerceAtLeast(0L)
                val endMs = ((end + offset) * 1000.0).toLong().coerceAtLeast(0L)
                val boundedEnd = if (durationMs > 0L) endMs.coerceAtMost(durationMs) else endMs
                if (boundedEnd <= startMs) return@mapNotNull null
                Triple(AniSkipSegment(startMs.coerceAtMost(boundedEnd - 1L), boundedEnd, label), sourceLength, type)
            }
            // Prefer the record closest to the actual file duration and a normal OP/ED over mixed variants.
            candidates.groupBy { it.first.label }.values.mapNotNull { group ->
                group.minWithOrNull(compareBy<Triple<AniSkipSegment, Double, String>>(
                    { if (durationSeconds > 0.0 && it.second > 0.0) abs(durationSeconds - it.second) else Double.MAX_VALUE },
                    { if (it.third.startsWith("mixed-")) 1 else 0 }
                ))?.first
            }.sortedBy { it.startMs }
        }

    private fun request(malId: Int, episodeNumber: Int, episodeLength: Double): List<JSONObject> {
        val url = URL(
            "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                "?types=op&types=ed&types=mixed-op&types=mixed-ed&episodeLength=$episodeLength"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AniSync-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (!root.optBoolean("found")) return emptyList()
            val array = root.optJSONArray("results") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}

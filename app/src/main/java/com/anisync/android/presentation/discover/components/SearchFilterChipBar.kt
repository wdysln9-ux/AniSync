package com.anisync.android.presentation.discover.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anisync.android.domain.ComparatorMode
import com.anisync.android.domain.IntComparatorFilter
import com.anisync.android.domain.IntRangeFilter
import com.anisync.android.domain.OriginCountry
import com.anisync.android.domain.SearchFilters
import com.anisync.android.domain.SearchSort
import com.anisync.android.presentation.util.toLabel

/**
 * AnimeW-style active-filter chips: one removable chip per selected value.
 * This is deliberately independent from the Discover filter-sheet chip controls.
 */
@Composable
fun SearchFilterChipBar(
    filters: SearchFilters,
    onFiltersChange: (SearchFilters) -> Unit,
    modifier: Modifier = Modifier
) {
    val chips = buildList {
        filters.genresIncluded.forEach { value ->
            add(value to filters.copy(genresIncluded = filters.genresIncluded - value))
        }
        filters.genresExcluded.forEach { value ->
            add("− $value" to filters.copy(genresExcluded = filters.genresExcluded - value))
        }
        filters.tagsIncluded.forEach { value ->
            add(value to filters.copy(tagsIncluded = filters.tagsIncluded - value))
        }
        filters.tagsExcluded.forEach { value ->
            add("− $value" to filters.copy(tagsExcluded = filters.tagsExcluded - value))
        }
        if (filters.yearRange.isActive) {
            val label = when {
                filters.yearRange.min != null && filters.yearRange.max != null ->
                    "${filters.yearRange.min}–${filters.yearRange.max}"
                filters.yearRange.min != null -> "${filters.yearRange.min}+"
                else -> "≤${filters.yearRange.max}"
            }
            add(label to filters.copy(yearRange = IntRangeFilter()))
        }
        filters.season?.let { season ->
            val label = when (season) {
                com.anisync.android.type.MediaSeason.WINTER -> "Winter"
                com.anisync.android.type.MediaSeason.SPRING -> "Spring"
                com.anisync.android.type.MediaSeason.SUMMER -> "Summer"
                com.anisync.android.type.MediaSeason.FALL -> "Fall"
                else -> season.toLabel().orEmpty()
            }
            add(label to filters.copy(season = null))
        }
        filters.formats.forEach { value ->
            add(value.toLabel().orEmpty() to filters.copy(formats = filters.formats - value))
        }
        filters.statuses.forEach { value ->
            add(value.toLabel().orEmpty() to filters.copy(statuses = filters.statuses - value))
        }
        filters.sources.forEach { value ->
            add(value.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } to
                filters.copy(sources = filters.sources - value))
        }
        filters.country?.let { country ->
            val label = when (country) {
                OriginCountry.JAPAN -> "Jepang"
                OriginCountry.SOUTH_KOREA -> "Korea Selatan"
                OriginCountry.CHINA -> "China"
                OriginCountry.TAIWAN -> "Taiwan"
            }
            add(label to filters.copy(country = null))
        }
        filters.score.takeIf { it.isActive }?.let { value ->
            val amount = value.value ?: 0
            val label = when {
                value.maxValue != null && amount > 0 -> "Skor $amount–${value.maxValue}"
                value.maxValue != null -> "Skor ≤ ${value.maxValue}"
                value.mode == ComparatorMode.AT_LEAST -> "Skor ≥ $amount"
                value.mode == ComparatorMode.AT_MOST -> "Skor ≤ $amount"
                value.mode == ComparatorMode.EXACTLY -> "Skor = $amount"
                else -> "Skor $amount"
            }
            add(label to filters.copy(score = IntComparatorFilter()))
        }
        filters.episodes.takeIf { it.isActive }?.let { value ->
            val amount = value.value ?: 0
            val label = when {
                value.maxValue != null && amount > 0 -> "Episode $amount–${value.maxValue}"
                value.maxValue != null -> "Episode ≤ ${value.maxValue}"
                value.mode == ComparatorMode.AT_LEAST -> "Episode ≥ $amount"
                value.mode == ComparatorMode.AT_MOST -> "Episode ≤ $amount"
                value.mode == ComparatorMode.EXACTLY -> "Episode = $amount"
                else -> "Episode $amount"
            }
            add(label to filters.copy(episodes = IntComparatorFilter()))
        }
        if (filters.hideMyAnime) {
            add("Hide My Anime" to filters.copy(hideMyAnime = false))
        }
        if (filters.sort != SearchSort()) {
            add("Urutan: ${filters.sort.column.label()}" to filters.copy(sort = SearchSort()))
        }
    }

    if (chips.isEmpty()) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { (label, remainingFilters) ->
            Surface(
                modifier = Modifier.clickable { onFiltersChange(remainingFilters) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Hapus filter $label",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

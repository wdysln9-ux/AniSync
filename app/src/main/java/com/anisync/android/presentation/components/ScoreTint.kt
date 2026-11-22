package com.anisync.android.presentation.components

import androidx.compose.ui.graphics.Color
import com.anisync.android.domain.ScoreFormat
import com.anisync.android.domain.max

/**
 * AnimeW-style score tint. The score badge keeps its dark background; only
 * the star and score value carry this semantic color.
 *
 * Community scores use AniList's 0–100 scale.
 */
fun communityScoreTint(score: Double): Color = when {
    score >= 85.0 -> Color(0xFFB388FF)
    score >= 75.0 -> Color(0xFF55D879)
    score >= 60.0 -> Color(0xFFFFC247)
    else -> Color(0xFFFF7168)
}

/** Converts a personal score to a percentage before applying the same tiers. */
fun personalScoreTint(score: Double, format: ScoreFormat): Color =
    communityScoreTint((score / format.max * 100.0).coerceIn(0.0, 100.0))

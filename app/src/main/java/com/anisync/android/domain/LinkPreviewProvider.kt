package com.anisync.android.domain

import com.anisync.android.domain.parser.LinkPreviewKey
import com.anisync.android.domain.parser.RichTextBlock

data class LinkPreview(
    val title: String,
    val imageUrl: String?,
    /** Average cover color as a `#RRGGBB` hex string (media only); used to tint the link card. */
    val coverColor: String? = null,
    /** Optional secondary line (media: format · year). */
    val subtitle: String? = null,
    /** Average AniList score on its 0–100 scale. */
    val score: Int? = null,
    /** Media genres, in AniList order. */
    val genres: List<String> = emptyList()
)

interface LinkPreviewProvider {
    suspend fun getPreviews(links: List<RichTextBlock.AnilistLink>): Map<LinkPreviewKey, LinkPreview>
}

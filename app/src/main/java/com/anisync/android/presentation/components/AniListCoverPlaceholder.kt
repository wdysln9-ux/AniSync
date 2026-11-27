package com.anisync.android.presentation.components

import androidx.compose.ui.graphics.Color

/** Parses AniList's #RRGGBB coverImage.color without allowing malformed API values to crash UI. */
internal fun String?.toAniListCoverColor(): Color? =
    this?.let { hex -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }

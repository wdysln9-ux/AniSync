package com.anisync.android.presentation.util

import androidx.compose.runtime.compositionLocalOf
import com.anisync.android.domain.LibraryEntry

/**
 * App-wide entry editor callback. Poster cards use this so a long press opens the same editor
 * regardless of which screen or rail currently displays the anime.
 */
val LocalLibraryEntryEditor = compositionLocalOf<(LibraryEntry) -> Unit> { {} }

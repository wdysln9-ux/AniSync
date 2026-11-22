package com.anisync.android.domain

import androidx.compose.runtime.Immutable
import java.util.Calendar

@Immutable
sealed interface SearchResult {
    val id: Int
    val displayName: String
    val imageUrl: String?

    @Immutable
    data class MediaResult(
        val entry: LibraryEntry
    ) : SearchResult {
        override val id: Int get() = entry.mediaId
        override val displayName: String get() = entry.titleUserPreferred
        override val imageUrl: String? get() = entry.coverUrl
    }

    @Immutable
    data class CharacterResult(
        override val id: Int,
        override val displayName: String,
        val nativeName: String?,
        override val imageUrl: String?,
        val favourites: Int?,
        val birthdayMonth: Int? = null,
        val birthdayDay: Int? = null
    ) : SearchResult

    @Immutable
    data class StaffResult(
        override val id: Int,
        override val displayName: String,
        val nativeName: String?,
        override val imageUrl: String?,
        val primaryOccupations: List<String>,
        val favourites: Int?,
        val birthdayMonth: Int? = null,
        val birthdayDay: Int? = null
    ) : SearchResult

    @Immutable
    data class UserResult(
        override val id: Int,
        override val displayName: String,
        override val imageUrl: String?,
        val donatorTier: Int = 0,
        val donatorBadge: String? = null,
        /** A serving moderator, whose role badge takes the donator badge's place on the website. */
        val isActiveModerator: Boolean = false
    ) : SearchResult

    @Immutable
    data class StudioResult(
        override val id: Int,
        override val displayName: String,
        val favourites: Int?
    ) : SearchResult {
        override val imageUrl: String? get() = null
    }
}

/**
 * Orders known birthdays by the next occurrence from today. Entries without a complete
 * month/day are kept at the end, matching AniList's birthday-first browse intent.
 */
private fun daysUntilBirthday(month: Int?, day: Int?): Int {
    if (month == null || day == null || month !in 1..12 || day !in 1..31) return Int.MAX_VALUE
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val next = (today.clone() as Calendar).apply {
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        if (before(today)) add(Calendar.YEAR, 1)
    }
    return ((next.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
}

@JvmName("sortedCharactersByUpcomingBirthday")
fun List<SearchResult.CharacterResult>.sortedByUpcomingBirthday(): List<SearchResult.CharacterResult> =
    sortedBy { daysUntilBirthday(it.birthdayMonth, it.birthdayDay) }

@JvmName("sortedStaffByUpcomingBirthday")
fun List<SearchResult.StaffResult>.sortedByUpcomingBirthday(): List<SearchResult.StaffResult> =
    sortedBy { daysUntilBirthday(it.birthdayMonth, it.birthdayDay) }

@Immutable
data class GroupedSearchResults(
    val anime: List<SearchResult.MediaResult> = emptyList(),
    val manga: List<SearchResult.MediaResult> = emptyList(),
    val characters: List<SearchResult.CharacterResult> = emptyList(),
    val staff: List<SearchResult.StaffResult> = emptyList(),
    val users: List<SearchResult.UserResult> = emptyList(),
    val studios: List<SearchResult.StudioResult> = emptyList()
) {
    val isEmpty: Boolean
        get() = anime.isEmpty() && manga.isEmpty() && characters.isEmpty() &&
                staff.isEmpty() && users.isEmpty() && studios.isEmpty()
}

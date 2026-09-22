package com.nexastream.app.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SearchFilters(
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val mediaType: MediaType = MediaType.ALL,
    val sortBy: SortBy = SortBy.POPULARITY_DESC,
    val voteAverageGte: Float? = null,
) : Parcelable {

    enum class MediaType {
        ALL, MOVIES, TV_SHOWS
    }

    enum class SortBy(val value: String) {
        POPULARITY_DESC("popularity.desc"),
        POPULARITY_ASC("popularity.asc"),
        VOTE_AVERAGE_DESC("vote_average.desc"),
        VOTE_AVERAGE_ASC("vote_average.asc"),
        RELEASE_DATE_DESC("primary_release_date.desc"),
        RELEASE_DATE_ASC("primary_release_date.asc")
    }

    fun isDefault(): Boolean {
        return genres.isEmpty() && year == null && mediaType == MediaType.ALL && sortBy == SortBy.POPULARITY_DESC && voteAverageGte == null
    }
}

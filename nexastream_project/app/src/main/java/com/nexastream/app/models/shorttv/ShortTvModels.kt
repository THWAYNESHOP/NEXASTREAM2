package com.nexastream.app.models.shorttv

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class BffBaseResp<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: T?
) {
    val isSuccess: Boolean get() = code == 0
}

@Keep
data class BffShortTvInfo(
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("subjectType") val subjectType: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("cover") val cover: BffShortTvImage?,
    @SerializedName("totalEpisode") val totalEpisode: Int?,
    @SerializedName("firstEp") val firstEpisode: BffShortTvEpisode?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?,
    @SerializedName("needPaid") val needPaid: Int?,
    @SerializedName("isPaid") val isPaid: Int?,
    @SerializedName("unlockType") val unlockType: List<Int>?,
    @SerializedName("isVip") val isVip: Boolean?
)

@Keep
data class BffShortTvEpisodeListData(
    @SerializedName("pager") val pager: BffShortTvPager?,
    @SerializedName("items") val items: List<BffShortTvEpisode>?,
    @SerializedName("info") val info: BffShortTvInfo?
)

@Keep
data class BffShortTvEpisode(
    @SerializedName("miniId") val miniId: String?,
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("se") val season: Int?,
    @SerializedName("ep") val episode: Int?,
    @SerializedName("videoId") val videoId: String?,
    @SerializedName("resourceId") val resourceId: String?,
    @SerializedName("video") val video: BffShortTvVideoInfo?,
    @SerializedName("lockStatus") val lockStatus: Int?,
    @SerializedName("needPaid") val needPaid: Int?
)

@Keep
data class BffShortTvVideoInfo(
    @SerializedName("addressList") val addressList: List<BffShortTvVideo>?,
    @SerializedName("cover") val cover: BffShortTvImage?
)

@Keep
data class BffShortTvVideo(
    @SerializedName("url") val url: String?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?,
    @SerializedName("size") val size: Long?,
    @SerializedName("resolution") val resolution: String?
)

@Keep
data class BffShortTvImage(
    @SerializedName("url") val url: String?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?,
    @SerializedName("blurHash") val blurHash: String?
)

@Keep
data class BffShortTvPager(
    @SerializedName("page") val page: Int?,
    @SerializedName("perPage") val perPage: Int?,
    @SerializedName("totalCount") val totalCount: Int?,
    @SerializedName("hasMore") val hasMore: Boolean?
)

@Keep
data class BffOperatingListData(
    @SerializedName("items") val items: List<BffOperatingItem>?,
    @SerializedName("version") val version: String?,
    @SerializedName("pager") val pager: BffPager?
)

@Keep
data class BffOperatingItem(
    @SerializedName("type") val type: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("subjects") val subjects: List<BffOperatingSubject>?
)

@Keep
data class BffOperatingSubject(
    @SerializedName("subjectId") val subjectId: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("cover") val cover: BffImage?,
    @SerializedName("bannerImage") val bannerImage: BffImage?,
    @SerializedName("imdbRatingValue") val imdbRatingValue: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("releaseDate") val releaseDate: String?
)

@Keep
data class BffImage(
    @SerializedName("url") val url: String?
)

@Keep
data class BffPager(
    @SerializedName("page") val page: Int?,
    @SerializedName("perPage") val perPage: Int?,
    @SerializedName("totalCount") val totalCount: Int?,
    @SerializedName("hasMore") val hasMore: Boolean?
)

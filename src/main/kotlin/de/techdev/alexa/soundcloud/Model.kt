package de.techdev.alexa.soundcloud

import java.net.URL
import java.time.Duration
import java.time.ZonedDateTime

data class ListResult<out T>(val collection: List<T>, val nextHref: URL?)

data class ActivityStream(
        val collection: List<ActivityElement<*>>,
        val nextHref: URL?,
        val futureHref: URL
) {
    /**
     * Returns only elements that are tracks.
     */
    fun tracks(): List<Track> = collection
            .filter { it.type == ActivityElementType.track_repost || it.type == ActivityElementType.track }
            .map { it.origin as Track }
}

enum class ActivityElementType {
    playlist, track, playlist_repost, track_repost
}

data class ActivityElement<out T : ActivityOrigin>(
        val origin: T,
        val tags: String?,
        val createdAt: ZonedDateTime,
        val type: ActivityElementType
)

/**
 * Just a marker interface for items from /me/activities/tracks/affiliated (can be playlists and tracks)
 */
interface ActivityOrigin

data class Playlist(
        val duration: Duration,
        val releaseDay: String?,
        val permalinkUrl: URL,
        val repostsCount: Long,
        val genre: String,
        val permalink: String,
        val purchaseUrl: URL?,
        val releaseMonth: Short?,
        val description: String?,
        val uri: URL,
        val labelName: String?,
        val tagList: String,
        val releaseYear: Int?,
        val secretUri: URL,
        val trackCount: Int,
        val userId: Long,
        val lastModified: ZonedDateTime,
        val license: License,
        val playlistType: String?,
        val tracksUri: URL,
        val downloadable: Boolean?,
        val sharing: String,
        val secretToken: String,
        val createdAt: ZonedDateTime,
        val release: String?,
        val likesCount: Long,
        val kind: String,
        val title: String,
        val type: String?,
        val purchaseTitle: String?,
        val createdWith: String?,
        val artworkUrl: URL?,
        val ean: String?,
        val streamable: Boolean,
        val user: User,
        val embeddableBy: String
) : ActivityOrigin

data class Track(
        val id: String,
        val createdAt: ZonedDateTime,
        val lastModified: ZonedDateTime,
        val permalink: String,
        val permalinkUrl: URL,
        val title: String,
        val duration: Duration,
        val sharing: String,
        val waveformUrl: URL,
        val streamUrl: URL,
        val uri: URL,
        val userId: Long,
        val artworkUrl: URL?,
        val commentCount: Long,
        val commentable: Boolean,
        val description: String,
        val downloadCount: Long,
        val downloadable: Boolean,
        val embeddableBy: String,
        val favoritingsCount: Long,
        val genre: String,
        val isrc: String,
        val labelId: String?,
        val labelName: String,
        val license: License,
        val originalContentSize: Long,
        val originalFormat: String,
        val playbackCount: Long,
        val purchaseTitle: String,
        val purchaseUrl: URL,
        val release: String,
        val releaseDay: Short,
        val releaseMonth: Short,
        val releaseYear: Int,
        val repostsCount: Long,
        val state: String,
        val streamable: Boolean,
        val tagList: String,
        val trackType: String,
        val user: User,
        val likesCount: Long,
        val attachmentsUri: String,
        val bpm: Short?,
        val keySignature: String
) : ActivityOrigin {
    fun displayImageUrl(): String? = (artworkUrl ?: user.avatarUrl)?.toExternalForm()
}

enum class License {
    NO_RIGHTS_RESERVED,
    ALL_RIGHTS_RESERVED,
    CC_BY,
    CC_BY_NC,
    CC_BY_ND,
    CC_BY_SA,
    CC_BY_NC_ND,
    CC_BY_NC_SA
}

data class User(
        val avatarUrl: URL?,
        val id: Long,
        val kind: String,
        val permalinkUrl: URL,
        val uri: String,
        val username: String,
        val permalink: String,
        val lastModified: ZonedDateTime
)
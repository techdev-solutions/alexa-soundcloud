package de.techdev.alexa.soundcloud

import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.document.DynamoDB
import com.amazonaws.services.dynamodbv2.document.Item
import com.amazonaws.services.dynamodbv2.document.Table
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap
import java.net.URL
import java.util.*

enum class PlaybackType {
    /**
     * Playback e.g. by searching for an artist or using the favorites
     */
    track_list,
    /**
     * Playback by using the user's stream
     */
    stream
}

class Playback(
        /**
         * List of API URLs for the tracks in the playlist
         */
        val trackList: List<URL>,
        /**
         * URL to load the next tracks from if trackList is at the end.
         */
        val soundCloudNextHref: URL?,
        /**
         * The current track that is playing
         */
        val position: Int,
        val offsetInMilliseconds: Long = 0,
        val looping: Boolean = false,
        val shuffle: Boolean = false,
        val type: PlaybackType
) {

    /**
     * Return the URL for the next track. Null if no track is available to play next. The caller could still try to
     * use nextTracksUrl to load more tracks!
     */
    fun nextTrackUrl(): Optional<URL> {
        if (position < trackList.size - 1) {
            return Optional.of(trackList[position + 1])
        } else if (position == trackList.size - 1 && looping && soundCloudNextHref == null) {
            return Optional.of(trackList[0])
        } else {
            return Optional.empty()
        }
    }

    fun previousTrackUrl(): Optional<URL> {
        if (position > 0) {
            return Optional.of(trackList[position - 1])
        } else {
            return Optional.empty()
        }
        // TODO what about looping - do we want "back" to jump over 0 to the last track? Super hard with pagination!
    }
}

class UserHasNoPlaybackException : Exception()

class SoundcloudSessions(private val accessor: SoundcloudAccessor) {

    private val table: Table

    init {
        val regionName = System.getenv("AWS_DEFAULT_REGION") ?: System.getenv("DYNAMODB_REGION")
        val region = Regions.fromName(regionName)
        val client = AmazonDynamoDBClientBuilder.standard().withRegion(region).build()
        val db = DynamoDB(client)
        val dynamoDbTableName = System.getenv("DYNAMODB_SESSIONS_TABLE") ?: "soundcloud-session"
        table = db.getTable(dynamoDbTableName)
    }

    /**
     * Stores the stream urls of the next tracks to play and the href to fetch more tracks
     *
     * Completely replaces the old information
     */
    fun storeNextTracks(userId: String, playback: Playback) {
        table.putItem(
                Item().withPrimaryKey("user", userId)
                        .withList("trackList", playback.trackList.map(URL::toExternalForm))
                        .withInt("playPosition", playback.position)
                        .withLong("offsetInMilliseconds", playback.offsetInMilliseconds)
                        .with("nextHref", playback.soundCloudNextHref?.toExternalForm())
                        .withBoolean("looping", playback.looping)
                        .withBoolean("shuffle", playback.shuffle)
                        .withString("playbackType", playback.type.toString())
        )
    }

    /**
     * Load the playback session for a user.
     */
    fun loadPlayback(userId: String): Playback {
        val item = table.getItem("user", userId) ?: throw UserHasNoPlaybackException()
        val nextTracksUrl = item.getString("nextHref")
        return Playback(
                trackList = item.getList<String>("trackList").map(::URL),
                soundCloudNextHref = if (nextTracksUrl == null) null else URL(nextTracksUrl),
                position = item.getInt("playPosition"),
                looping = item.getBoolean("looping"),
                shuffle = item.getBoolean("shuffle"),
                offsetInMilliseconds = item.getLong("offsetInMilliseconds"),
                type = PlaybackType.valueOf(item.getString("playbackType"))
        )
    }

    /**
     * @param accessToken The oauth token for the user. Only needed if getting the next track while playing the users stream. Otherwise it can be null.
     */
    fun getNextTrack(userId: String, accessToken: String?): Optional<Track> {
        val playback = loadPlayback(userId)
        val nextTrackUrl = playback.nextTrackUrl()
        if (nextTrackUrl.isPresent) {
            return nextTrackUrl.map { accessor.track(it) }
        } else if (playback.soundCloudNextHref != null) {
            val additionalTracks = when (playback.type) {
                PlaybackType.track_list -> accessor.searchTracks(playback.soundCloudNextHref)
                PlaybackType.stream -> {
                    if(accessToken == null) throw IllegalStateException("Cannot continue stream without an OAuth token. Something went wrong!")
                    val stream = accessor.continueStream(playback.soundCloudNextHref, accessToken)
                    ListResult(stream.tracks(), stream.nextHref)
                }
            }

            val spec = UpdateItemSpec()
                    .withPrimaryKey("user", userId)
                    .withUpdateExpression("set trackList = list_append(trackList, :additionalTracks), nextHref = :nextHref")
                    .withValueMap(ValueMap()
                            .withList(":additionalTracks", additionalTracks.collection.map { it.uri.toExternalForm() })
                            .with(":nextHref", additionalTracks.nextHref?.toExternalForm())
                    )
            table.updateItem(spec)
            // TODO in case of the stream result this could be empty!
            return Optional.of(additionalTracks.collection[0])
        } else {
            return Optional.empty()
        }
    }

    fun getPreviousTrack(userId: String): Optional<Track> {
        val playback = loadPlayback(userId)
        val previousTrackUrl = playback.previousTrackUrl()
        return previousTrackUrl.map { accessor.track(it) }
    }

    /**
     * Update the play position to the track with the given URL
     */
    fun updatePosition(userId: String, trackUrl: URL) {
        val playback = loadPlayback(userId)
        val position = playback.trackList.indexOf(trackUrl)
        if (position < 0) {
            throw IllegalStateException("Could not find track $trackUrl in the playback state!")
        }
        val spec = UpdateItemSpec()
                .withPrimaryKey("user", userId)
                .withUpdateExpression("set playPosition = :newPosition")
                .withValueMap(ValueMap()
                        .withInt(":newPosition", position)
                )
        table.updateItem(spec)
    }

    /**
     * Updates the position of {@code trackUrl} in the track list and the current offset. Call this when you want to pause playback.
     */
    fun rememberOffsetAndPosition(userId: String, trackUrl: URL, offsetInMilliseconds: Long) {
        val playback = loadPlayback(userId)
        val position = playback.trackList.indexOf(trackUrl)
        if (position < 0) {
            throw IllegalStateException("Could not find track $trackUrl in the playback state!")
        }
        val spec = UpdateItemSpec()
                .withPrimaryKey("user", userId)
                .withUpdateExpression("set offsetInMilliseconds = :offsetInMilliseconds, playPosition = :playPosition")
                .withValueMap(ValueMap()
                        .withLong(":offsetInMilliseconds", offsetInMilliseconds)
                        .withInt(":playPosition", position))
        table.updateItem(spec)
    }

    fun setLoopOn(userId: String) {
        val spec = UpdateItemSpec()
                .withPrimaryKey("user", userId)
                .withUpdateExpression("set looping = :looping")
                .withValueMap(ValueMap().withBoolean(":looping", true))
        table.updateItem(spec)
    }

    fun setLoopOff(userId: String) {
        val spec = UpdateItemSpec()
                .withPrimaryKey("user", userId)
                .withUpdateExpression("set looping = :looping")
                .withValueMap(ValueMap().withBoolean(":looping", false))
        table.updateItem(spec)
    }
}
package de.techdev.alexa.soundcloud

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class SoundcloudAccessor {

    private val SOUNDCLOUD_CLIENT_ID = System.getenv("SOUNDCLOUD_CLIENT_ID") ?: "ENTER_YOUR_SOUNDCLOUND_CLIENT_ID"

    private val logger = LoggerFactory.getLogger(SoundcloudAccessor::class.java)
    private val client = OkHttpClient().newBuilder().followRedirects(false).build()
    private val gson: Gson

    init {
        gson = GsonBuilder()
                .setFieldNamingStrategy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(ZonedDateTime::class.java,
                        JsonDeserializer { json, _, _ -> ZonedDateTime.parse(json.asString, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z")) })
                .registerTypeAdapter(Duration::class.java,
                        JsonDeserializer { json, _, _ -> Duration.ofMillis(json.asLong) })
                .registerTypeAdapter(License::class.java,
                        JsonDeserializer { json, _, _ ->
                            when (json.asString) {
                                "no-rights-reserved" -> License.NO_RIGHTS_RESERVED
                                "all-rights-reserved" -> License.ALL_RIGHTS_RESERVED
                                "cc-by" -> License.CC_BY
                                "cc-by-nc" -> License.CC_BY_NC
                                "cc-by-nd" -> License.CC_BY_ND
                                "cc-by-sa" -> License.CC_BY_SA
                                "cc-by-nc-nd" -> License.CC_BY_NC_ND
                                "cc-by-nc-sa" -> License.CC_BY_NC_SA
                                else -> null
                            }
                        })
                .registerTypeAdapter(ActivityElement::class.java, JsonDeserializer { json, _, ctx ->
                    val jsonObject = json.asJsonObject
                    val activityType: ActivityElementType
                    try {
                        activityType = ActivityElementType.valueOf(jsonObject["type"].asString.replace('-', '_'))
                    } catch(e: Exception) {
                        logger.warn("No activity of type {} registered.", jsonObject["type"].asString)
                        return@JsonDeserializer null
                    }
                    val originJson = jsonObject["origin"]
                    val origin = when (activityType) {
                        ActivityElementType.playlist -> ctx.deserialize<Playlist>(originJson, Playlist::class.java)
                        ActivityElementType.playlist_repost -> ctx.deserialize<Playlist>(originJson, Playlist::class.java)
                        ActivityElementType.track -> ctx.deserialize<Track>(originJson, Track::class.java)
                        ActivityElementType.track_repost -> ctx.deserialize(originJson, Track::class.java)
                    }
                    val tags = if (jsonObject["tags"].isJsonNull) null else jsonObject["tags"].asString
                    ActivityElement(origin, tags, ctx.deserialize(jsonObject["created_at"], ZonedDateTime::class.java), activityType)
                })
                .create()
    }

    fun searchTracks(url: URL): ListResult<Track> {
        return listResult(HttpUrl.get(url))
    }

    fun track(url: URL): Track {
        val httpUrl = HttpUrl.get(url).newBuilder().addQueryParameter("client_id", SOUNDCLOUD_CLIENT_ID).build()
        val request = Request.Builder().url(httpUrl).build()
        val response = client.newCall(request).execute()
        val track = gson.fromJson(InputStreamReader(response.body().byteStream()), Track::class.java)
        response.close()
        return track
    }

    private fun listResult(url: HttpUrl): ListResult<Track> {
        val request = Request.Builder().url(url).get().build()
        val response: Response
        try {
            response = client.newCall(request).execute()
        } catch (e: IOException) {
            throw IllegalStateException(e)
        }

        val tracks = gson.fromJson<ListResult<Track>>(InputStreamReader(response.body().byteStream()), TypeToken.getParameterized(ListResult::class.java, Track::class.java).type)
        response.close()
        return tracks
    }

    fun convertTrackStreamUrl(publicStreamUrl: URL): URL {
        val url = HttpUrl.get(publicStreamUrl).newBuilder().addQueryParameter("client_id", SOUNDCLOUD_CLIENT_ID).build()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val redirectLocation = response.header("Location")
        // TODO some tracks might not be streamable, (e.g. test with Korn), do they return 401 here?
        response.close()
        return URL(redirectLocation)
    }

    fun getFavorites(accessToken: String): ListResult<Track> {
        val url = HttpUrl.parse("https://api.soundcloud.com/me/favorites").newBuilder()
                .addQueryParameter("linked_partitioning", "true")
                .addQueryParameter("oauth_token", accessToken).build()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val tracks = gson.fromJson<ListResult<Track>>(InputStreamReader(response.body().byteStream()), TypeToken.getParameterized(ListResult::class.java, Track::class.java).type)
        response.close()
        return tracks
    }

    fun likeTrack(accessToken: String, track: Track) {
        val url = HttpUrl.parse("https://api.soundcloud.com/me/favorites/${track.id}").newBuilder()
                .addQueryParameter("oauth_token", accessToken).build()
        val request = Request.Builder().put(RequestBody.create(MediaType.parse("application/json"), "")).url(url).build()
        val response = client.newCall(request).execute()
        response.close()
    }

    /**
     * Follow the user. Returns an empty optional if the user is already being followed.
     */
    fun follow(accessToken: String, user: User): Optional<String> {
        val url = HttpUrl.parse("https://api.soundcloud.com/me/followings/${user.id}").newBuilder()
                .addQueryParameter("oauth_token", accessToken).build()
        val alreadyFollowingRequest = Request.Builder().url(url).get().build()
        val alreadyFollowingResponse = client.newCall(alreadyFollowingRequest).execute()

        if (alreadyFollowingResponse.code() == 404) {
            val request = Request.Builder().url(url).put(RequestBody.create(MediaType.parse("application/json"), "")).build()
            val response = client.newCall(request).execute()
            response.close()
            return Optional.of("Ok")
        }
        alreadyFollowingResponse.close()
        return Optional.empty()
    }

    /**
     * Loads the activity stream for the given user
     */
    fun getActivityStream(accessToken: String): ActivityStream {
        val url = HttpUrl.parse("https://api.soundcloud.com/me/activities/tracks/affiliated").newBuilder()
                .addQueryParameter("oauth_token", accessToken).build()
        return activityStream(url)
    }

    /**
     * Continue the stream (by executing the call to the next_href URL)
     */
    fun continueStream(soundCloudNextHref: URL, accessToken: String): ActivityStream =
            activityStream(HttpUrl.get(soundCloudNextHref).newBuilder().addQueryParameter("oauth_token", accessToken).build())

    private fun activityStream(url: HttpUrl): ActivityStream {
        val request = Request.Builder().get().url(url).build()
        val response = client.newCall(request).execute()
        val stream = gson.fromJson(InputStreamReader(response.body().byteStream()), ActivityStream::class.java)
        response.close()
        return stream.copy(collection = stream.collection.filterNotNull())
    }
}

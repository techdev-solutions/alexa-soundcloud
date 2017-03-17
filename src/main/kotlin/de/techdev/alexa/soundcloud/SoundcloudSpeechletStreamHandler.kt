package de.techdev.alexa.soundcloud

import com.amazon.speech.json.SpeechletRequestEnvelope
import com.amazon.speech.speechlet.*
import com.amazon.speech.speechlet.interfaces.audioplayer.*
import com.amazon.speech.speechlet.interfaces.audioplayer.directive.PlayDirective
import com.amazon.speech.speechlet.interfaces.audioplayer.directive.StopDirective
import com.amazon.speech.speechlet.interfaces.audioplayer.request.*
import com.amazon.speech.speechlet.interfaces.system.SystemInterface
import com.amazon.speech.speechlet.interfaces.system.SystemState
import com.amazon.speech.speechlet.lambda.SpeechletRequestStreamHandler
import com.amazon.speech.ui.*
import org.slf4j.LoggerFactory
import java.net.URL

class SoundcloudSpeechletStreamHandler : SpeechletRequestStreamHandler(SoundcloudSpeechlet(), setOf(System.getenv("ALEXA_SKILL_ID")))

internal class SoundcloudSpeechlet : SpeechletV2, AudioPlayer {

    private val logger = LoggerFactory.getLogger(SoundcloudSpeechlet::class.java)
    private val accessor = SoundcloudAccessor()
    private val sessions = SoundcloudSessions(accessor)

    override fun onSessionStarted(envelope: SpeechletRequestEnvelope<SessionStartedRequest>) {

    }

    override fun onLaunch(envelope: SpeechletRequestEnvelope<LaunchRequest>): SpeechletResponse {
        val translator = Translator(envelope.request.locale)
        val speech = PlainTextOutputSpeech()
        speech.text = translator.getTranslation("LaunchIntent.speech")
        val reprompt = Reprompt()
        reprompt.outputSpeech = PlaintextOutputSpeech("LaunchIntent.reprompt.speech")
        return SpeechletResponse.newAskResponse(speech, reprompt)
    }

    override fun onIntent(envelope: SpeechletRequestEnvelope<IntentRequest>): SpeechletResponse {
        val intent = envelope.request.intent.name
        val translator = Translator(envelope.request.locale)
        logger.debug("Intent request {}", intent)
        when (intent) {
            "PlayMyFavoritesIntent" -> {
                if (envelope.session.user.accessToken == null) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("LinkAccount.speech"))
                    return SpeechletResponse.newTellResponse(speech, LinkAccountCard())
                }
                val tracks = accessor.getFavorites(envelope.session.user.accessToken)
                if (tracks.collection.isEmpty()) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("PlayMyFavoritesIntent.noFavorites.speech"))
                    return SpeechletResponse.newTellResponse(speech)
                }
                sessions.storeNextTracks(envelope.session.user.userId, Playback(tracks.collection.map { it.uri }, tracks.nextHref, 0, type = PlaybackType.track_list))
                val firstTrack = tracks.collection[0]
                val response = playResponse(firstTrack, PlayBehavior.REPLACE_ALL, 0)
                val cardText = translator.getTranslation("PlayMyFavoritesIntent.card.text", firstTrack.title, firstTrack.user.username, firstTrack.permalinkUrl)
                response.card = StandardCard(firstTrack.title, cardText, Image(firstTrack.displayImageUrl()))
                return response
            }
            "PlayMyStreamIntent" -> {
                if (envelope.session.user.accessToken == null) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("LinkAccount.speech"))
                    return SpeechletResponse.newTellResponse(speech, LinkAccountCard())
                }
                val stream = accessor.getActivityStream(envelope.session.user.accessToken)
                val tracks = stream.tracks()
                if (tracks.isEmpty() /* && stream.nextHref == null TODO handle this differently, there might be more to load from nextHref!*/) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("PlayMyStreamIntent.noTracks.speech"))
                    val card = SimpleCard(translator.getTranslation("PlayMyStreamIntent.noTracks.card.title"),
                            translator.getTranslation("PlayMyStreamIntent.noTracks.card.text"))
                    return SpeechletResponse.newTellResponse(speech, card)
                }
                sessions.storeNextTracks(envelope.session.user.userId, Playback(tracks.map { it.uri }, stream.nextHref, 0, type = PlaybackType.stream))
                val firstTrack = tracks[0]
                val response = playResponse(firstTrack, PlayBehavior.REPLACE_ALL, 0)
                val cardText = translator.getTranslation("PlayMyStreamIntent.card.text", firstTrack.title, firstTrack.user.username, firstTrack.permalinkUrl)
                response.card = StandardCard(firstTrack.title, cardText, Image(firstTrack.displayImageUrl()))
                return response
            }
            "LikeTrackIntent" -> {
                if (envelope.session.user.accessToken == null) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("LinkAccount.speech"))
                    return SpeechletResponse.newTellResponse(speech, LinkAccountCard())
                }
                val audioState = envelope.context.getState(AudioPlayerInterface::class.java, AudioPlayerState::class.java)
                if (audioState?.token == null) {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("LikeTrackIntent.noPlayback")))
                }
                val trackUrl = URL(audioState.token)
                val track = accessor.track(trackUrl)
                accessor.likeTrack(envelope.session.user.accessToken, track)
                return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("LikeTrackIntent.speech")))
            }
            "FollowUserIntent" -> {
                if (envelope.session.user.accessToken == null) {
                    val speech = PlaintextOutputSpeech(translator.getTranslation("LinkAccount.speech"))
                    return SpeechletResponse.newTellResponse(speech, LinkAccountCard())
                }
                val audioState = envelope.context.getState(AudioPlayerInterface::class.java, AudioPlayerState::class.java)
                if (audioState?.token == null) {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("FollowUserIntent.noPlayback")))
                }
                val trackUrl = URL(audioState.token)
                val track = accessor.track(trackUrl)
                val result = accessor.follow(envelope.session.user.accessToken, track.user)

                val response = SpeechletResponse()
                result.ifPresent {
                    val cardTitle = translator.getTranslation("FollowUserIntent.card.title", track.user.username)
                    val cardText = translator.getTranslation("FollowUserIntent.card.text", track.user.username)
                    val card = StandardCard(cardTitle, cardText, Image(track.user.avatarUrl?.toExternalForm()))
                    response.card = card
                }
                response.outputSpeech = PlaintextOutputSpeech(translator.getTranslation("FollowUserIntent.speech"))
                return response
            }
            "TellCurrentTrackIntent" -> {
                val audioState = envelope.context.getState(AudioPlayerInterface::class.java, AudioPlayerState::class.java)
                if (audioState?.token == null) {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("TellCurrentTrackIntent.noPlayback")))
                }
                val trackUrl = URL(audioState.token)
                val track = accessor.track(trackUrl)
                val cardTitle = translator.getTranslation("TellCurrentTrackIntent.card.title", track.title)
                val cardText = translator.getTranslation("TellCurrentTrackIntent.card.text", track.title, track.user.username, track.permalinkUrl)
                val speech = PlaintextOutputSpeech(translator.getTranslation("TellCurrentTrackIntent.speech", track.title))
                return SpeechletResponse.newTellResponse(speech, StandardCard(cardTitle, cardText, Image(track.displayImageUrl())))
            }
            "AMAZON.NextIntent" -> {
                val track = sessions.getNextTrack(envelope.session.user.userId, envelope.session.user.accessToken)
                return track.map { playResponse(it, PlayBehavior.REPLACE_ALL, 0) }.orElse(SpeechletResponse())
            }
            "AMAZON.PreviousIntent" -> {
                val track = sessions.getPreviousTrack(envelope.session.user.userId)
                return track.map { playResponse(it, PlayBehavior.REPLACE_ALL, 0) }.orElse(SpeechletResponse())
            }
            "AMAZON.LoopOnIntent" -> {
                sessions.setLoopOn(envelope.session.user.userId)
                return SpeechletResponse()
            }
            "AMAZON.LoopOffIntent" -> {
                sessions.setLoopOff(envelope.session.user.userId)
                return SpeechletResponse()
            }
            "AMAZON.StopIntent",
            "AMAZON.CancelIntent",
            "AMAZON.PauseIntent" -> {
                return SpeechletResponse(StopDirective())
            }
            "AMAZON.ResumeIntent" -> {
                val playback = sessions.loadPlayback(envelope.session.user.userId)
                val track = accessor.track(playback.trackList[playback.position])
                logger.debug("Resuming playback for track {} from {}", track.uri, playback.offsetInMilliseconds)
                return playResponse(track, PlayBehavior.REPLACE_ALL, playback.offsetInMilliseconds)
            }
            "AMAZON.RepeatIntent" -> {
                val audioState = envelope.context.getState(AudioPlayerInterface::class.java, AudioPlayerState::class.java)
                if (audioState?.token != null) {
                    val track = accessor.track(URL(audioState.token))
                    logger.debug("Repeating track {}", track.uri)
                    return playResponse(track, PlayBehavior.REPLACE_ENQUEUED, 0)
                } else {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("RepeatIntent.noPlayback")))
                }
            }
            "AMAZON.ShuffleOffIntent" -> {
                return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("ShuffleOffIntent.speech")))
            }
            "AMAZON.ShuffleOnIntent" -> {
                return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("ShuffleOnIntent.speech")))
            }
            "AMAZON.StartOverIntent" -> {
                val playback: Playback
                try {
                    playback = sessions.loadPlayback(envelope.session.user.userId)
                } catch(e: UserHasNoPlaybackException) {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("StartOverIntent.noTracks")))
                }
                if (playback.trackList.isEmpty()) {
                    return SpeechletResponse.newTellResponse(PlaintextOutputSpeech(translator.getTranslation("StartOverIntent.noTracks")))
                }
                val track = accessor.track(playback.trackList[0])
                return playResponse(track, PlayBehavior.REPLACE_ALL, 0)
            }
            "AMAZON.HelpIntent" -> {
                val card = SimpleCard(translator.getTranslation("HelpIntent.card.title"), translator.getTranslation("HelpIntent.card.text"))
                val speech = SsmlOutputSpeech()
                speech.ssml = translator.getTranslation("HelpIntent.speech")
                val reprompt = Reprompt()
                reprompt.outputSpeech = PlaintextOutputSpeech(translator.getTranslation("HelpIntent.reprompt.speech"))
                return SpeechletResponse.newAskResponse(speech, reprompt, card)
            }
            else -> {
                val speech = PlainTextOutputSpeech()
                speech.text = translator.getTranslation("UnknownIntent.speech")
                val reprompt = Reprompt()
                reprompt.outputSpeech = speech
                return SpeechletResponse.newAskResponse(speech, reprompt)
            }
        }
    }

    override fun onPlaybackFailed(envelope: SpeechletRequestEnvelope<PlaybackFailedRequest>): SpeechletResponse? {
        val systemState = envelope.context.getState(SystemInterface::class.java, SystemState::class.java)
        logger.debug("Playback failed request. Token {}, User {}", envelope.request.token, systemState.user.userId)
        logger.error("Playback failed request with error: {}", envelope.request.error)
        return null
    }

    override fun onPlaybackStarted(envelope: SpeechletRequestEnvelope<PlaybackStartedRequest>): SpeechletResponse? {
        val systemState = envelope.context.getState(SystemInterface::class.java, SystemState::class.java)
        logger.debug("Playback started request. Token {}, Offset {}, User {}", envelope.request.token, envelope.request.offsetInMilliseconds, systemState.user.userId)
        sessions.updatePosition(systemState.user.userId, URL(envelope.request.token))
        return null
    }

    override fun onPlaybackFinished(envelope: SpeechletRequestEnvelope<PlaybackFinishedRequest>): SpeechletResponse? = null

    /**
     * Called to queue the next track.
     *
     * This can be called several times - e.g. after a pause/resume!
     *
     * It is NOT allowed to return speech or a card!
     */
    override fun onPlaybackNearlyFinished(envelope: SpeechletRequestEnvelope<PlaybackNearlyFinishedRequest>): SpeechletResponse? {
        val systemState = envelope.context.getState(SystemInterface::class.java, SystemState::class.java)
        logger.debug("Playback nearly finished request. Token {}, Offset {}, User {}", envelope.request.token, envelope.request.offsetInMilliseconds, systemState.user.userId)
        val track = sessions.getNextTrack(systemState.user.userId, systemState.user.accessToken)
        return track.map {
            logger.debug("Enqueuing track {}", it.uri)
            playResponse(it, PlayBehavior.ENQUEUE, 0, expectedPreviousToken = envelope.request.token)
        }.orElse(null)
    }

    override fun onPlaybackStopped(envelope: SpeechletRequestEnvelope<PlaybackStoppedRequest>): SpeechletResponse? {
        val systemState = envelope.context.getState(SystemInterface::class.java, SystemState::class.java)
        logger.debug("Playback stopped request. Token {}, Offset {}, User {}", envelope.request.token, envelope.request.offsetInMilliseconds, systemState.user.userId)
        sessions.rememberOffsetAndPosition(systemState.user.userId, URL(envelope.request.token), envelope.request.offsetInMilliseconds)
        logger.debug("Remembering playback offset {} for track {}", envelope.request.offsetInMilliseconds, envelope.request.token)
        return null
    }

    private fun playResponse(track: Track, playBehavior: PlayBehavior, offsetInMilliseconds: Long, expectedPreviousToken: String? = null): SpeechletResponse {
        val stream = Stream(accessor.convertTrackStreamUrl(track.streamUrl).toExternalForm(), track.uri.toExternalForm(), expectedPreviousToken, offsetInMilliseconds)
        val directive = PlayDirective(stream, playBehavior)
        return SpeechletResponse(directive)
    }

    override fun onSessionEnded(envelope: SpeechletRequestEnvelope<SessionEndedRequest>) {

    }
}

fun PlaintextOutputSpeech(text: String): PlainTextOutputSpeech {
    val speech = com.amazon.speech.ui.PlainTextOutputSpeech()
    speech.text = text
    return speech
}

fun SpeechletResponse(vararg directives: Directive): SpeechletResponse {
    val response = com.amazon.speech.speechlet.SpeechletResponse()
    response.directives = directives.asList()
    return response
}

fun Stream(url: String, token: String, expectedPreviousToken: String? = null, offsetInMilliseconds: Long): Stream {
    val stream = Stream()
    stream.url = url
    stream.token = token
    if (expectedPreviousToken != null) {
        stream.expectedPreviousToken = expectedPreviousToken
    }
    stream.offsetInMilliseconds = offsetInMilliseconds
    return stream
}

fun PlayDirective(stream: Stream, playBehavior: PlayBehavior): PlayDirective {
    val audioItem = AudioItem()
    audioItem.stream = stream
    val directive = PlayDirective()
    directive.audioItem = audioItem
    directive.playBehavior = playBehavior
    return directive
}

fun Image(largeImageUrl: String?, smallImageUrl: String? = null): Image {
    val image = Image()
    image.largeImageUrl = largeImageUrl
    if (smallImageUrl == null) {
        image.smallImageUrl = largeImageUrl
    } else {
        image.smallImageUrl = smallImageUrl
    }
    return image
}

fun StandardCard(title: String, text: String, image: Image? = null): StandardCard {
    val card = StandardCard()
    card.title = title
    card.text = text
    card.image = image
    return card
}

fun SimpleCard(title: String, content: String): SimpleCard {
    val card = SimpleCard()
    card.title = title
    card.content = content
    return card
}
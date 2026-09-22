package ch.lqy.babyphone.media

import ch.lqy.babyphone.net.CallRequest
import ch.lqy.babyphone.net.IceCandidateDto
import ch.lqy.babyphone.net.LightMode
import ch.lqy.babyphone.net.LightRequest
import ch.lqy.babyphone.net.QualityRequest
import ch.lqy.babyphone.net.SignalKinds
import ch.lqy.babyphone.net.VideoRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call, without a phone. Everything awkward about setting one up — a candidate arriving
 * before the offer it belongs to, two phones asking at once, a stop from somebody who was never
 * in the call — is decided here, so it can be tested here.
 */
class CallSessionTest {
    @Test
    fun `listening asks the other phone to send, audio only`() {
        val (session, effects) = CallSession().on(CallEvent.Listen(Speaker))

        assertEquals(CallState.Asking(Speaker), session.state)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Start, audioOnly)), effects)
    }

    @Test
    fun `watching asks for the camera as well`() {
        val (_, effects) = CallSession().on(CallEvent.Listen(Speaker, video = true))

        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Start, withVideo)), effects)
    }

    @Test
    fun `being asked opens the microphone and offers`() {
        val (session, effects) = CallSession().on(CallEvent.Asked(Listener, allowed = true))

        assertEquals(CallState.Negotiating(Listener, CallRole.Speaker), session.state)
        assertEquals(
            listOf(
                CallEffect.Open(CallRole.Speaker, video = false, quality = CaptureQuality.Standard),
                CallEffect.CreateOffer
            ),
            effects
        )
    }

    @Test
    fun `being asked to be watched opens the camera too`() {
        val (_, effects) = CallSession().on(CallEvent.Asked(Listener, allowed = true, video = true))

        assertEquals(
            listOf(
                CallEffect.Open(CallRole.Speaker, video = true, quality = CaptureQuality.Standard),
                CallEffect.CreateOffer
            ),
            effects
        )
    }

    @Test
    fun `answering never opens this phone's own camera`() {
        val asking = CallSession().on(CallEvent.Listen(Speaker, video = true)).first

        val (_, effects) = asking.on(CallEvent.OfferReceived(Speaker, "v=0"))

        assertEquals(
            listOf(
                CallEffect.Open(CallRole.Listener, video = false, quality = CaptureQuality.Standard),
                CallEffect.AcceptOffer("v=0")
            ),
            effects
        )
    }

    @Test
    fun `a phone whose key was never confirmed is refused, not answered`() {
        val (session, effects) = CallSession().on(CallEvent.Asked(Listener, allowed = false))

        assertEquals(CallState.Idle, session.state)
        assertEquals(listOf(CallEffect.Send(Listener, SignalKinds.Stop, CallSession.Refused)), effects)
    }

    @Test
    fun `a second phone asking during a call is told the line is busy`() {
        val live = CallSession().listening()

        val (session, effects) = live.on(CallEvent.Asked(Other, allowed = true))

        assertEquals(live.state, session.state)
        assertEquals(listOf(CallEffect.Send(Other, SignalKinds.Stop, CallSession.Busy)), effects)
    }

    @Test
    fun `the phone already in the call asking again starts a new one`() {
        val live = CallSession()
            .on(CallEvent.Asked(Listener, allowed = true, video = true)).first
            .on(CallEvent.LocalOffer("v=0")).first
            .on(CallEvent.AnswerReceived(Listener, "v=0 answer")).first
            .on(CallEvent.Connected).first

        // Its network dropped and came back; this end never noticed the call had gone.
        val (session, effects) = live.on(CallEvent.Asked(Listener, allowed = true, video = true))

        assertEquals(CallState.Negotiating(Listener, CallRole.Speaker), session.state)
        assertEquals(
            listOf(
                CallEffect.Close,
                CallEffect.Open(CallRole.Speaker, video = true, quality = CaptureQuality.Standard),
                CallEffect.CreateOffer
            ),
            effects
        )
    }

    @Test
    fun `an offer from a phone that was not asked is refused`() {
        val asking = CallSession().on(CallEvent.Listen(Speaker)).first

        val (session, effects) = asking.on(CallEvent.OfferReceived(Other, "v=0"))

        assertEquals(CallState.Asking(Speaker), session.state)
        assertEquals(listOf(CallEffect.Send(Other, SignalKinds.Stop, CallSession.Busy)), effects)
    }

    @Test
    fun `candidates that arrive before the offer is applied are kept, not dropped`() {
        val asking = CallSession().on(CallEvent.Listen(Speaker)).first

        val (waiting, nothingYet) = asking.on(CallEvent.CandidateReceived(Speaker, CANDIDATE))

        assertEquals(emptyList<CallEffect>(), nothingYet)
        assertEquals(listOf(CANDIDATE), waiting.pendingCandidates)

        // The answer is what proves the offer went in, and the queue goes out behind it.
        val (negotiating, _) = waiting.on(CallEvent.OfferReceived(Speaker, "v=0"))
        val (live, effects) = negotiating.on(CallEvent.LocalAnswer("v=0 answer"))

        assertEquals(
            listOf(
                CallEffect.Send(Speaker, SignalKinds.Answer, "v=0 answer"),
                CallEffect.AddCandidate(CANDIDATE)
            ),
            effects
        )
        assertTrue(live.pendingCandidates.isEmpty())
    }

    @Test
    fun `candidates after the description go straight through`() {
        val negotiating = CallSession()
            .on(CallEvent.Listen(Speaker)).first
            .on(CallEvent.OfferReceived(Speaker, "v=0")).first
            .on(CallEvent.LocalAnswer("v=0 answer")).first

        val (_, effects) = negotiating.on(CallEvent.CandidateReceived(Speaker, CANDIDATE))

        assertEquals(listOf(CallEffect.AddCandidate(CANDIDATE)), effects)
    }

    @Test
    fun `the answer releases what was waiting on it`() {
        val offering = CallSession()
            .on(CallEvent.Asked(Listener, allowed = true)).first
            .on(CallEvent.LocalOffer("v=0 offer")).first
            .on(CallEvent.CandidateReceived(Listener, CANDIDATE)).first

        val (session, effects) = offering.on(CallEvent.AnswerReceived(Listener, "v=0 answer"))

        assertEquals(
            listOf(CallEffect.AcceptAnswer("v=0 answer"), CallEffect.AddCandidate(CANDIDATE)),
            effects
        )
        assertTrue(session.pendingCandidates.isEmpty())
    }

    @Test
    fun `connecting is what makes it live`() {
        val negotiating = CallSession()
            .on(CallEvent.Listen(Speaker)).first
            .on(CallEvent.OfferReceived(Speaker, "v=0")).first

        val (session, _) = negotiating.on(CallEvent.Connected)

        assertEquals(CallState.Live(Speaker, CallRole.Listener), session.state)
    }

    @Test
    fun `the picture size travels with the request to listen`() {
        val chosen = CallSession().on(CallEvent.ChooseQuality(CaptureQuality.High)).first

        val (_, effects) = chosen.on(CallEvent.Listen(Speaker, video = true))

        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Start, highVideo)), effects)
    }

    @Test
    fun `the phone that films opens its camera at the size it was asked for`() {
        val (session, effects) = CallSession().on(
            CallEvent.Asked(Listener, allowed = true, video = true, quality = CaptureQuality.Low)
        )

        assertEquals(CaptureQuality.Low, session.quality)
        assertEquals(
            listOf(CallEffect.Open(CallRole.Speaker, video = true, quality = CaptureQuality.Low), CallEffect.CreateOffer),
            effects
        )
    }

    @Test
    fun `changing the size mid-call sends it rather than dropping the call`() {
        val (session, effects) = CallSession().listening().on(CallEvent.ChooseQuality(CaptureQuality.High))

        assertEquals(CaptureQuality.High, session.quality)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Quality, high)), effects)
    }

    @Test
    fun `the phone that films does not choose the picture size`() {
        val sending = CallSession()
            .on(CallEvent.Asked(Listener, allowed = true, video = true)).first
            .on(CallEvent.LocalOffer("v=0")).first

        val (_, effects) = sending.on(CallEvent.ChooseQuality(CaptureQuality.High))

        assertEquals(emptyList<CallEffect>(), effects)
    }

    @Test
    fun `a size asked for by the phone in the call retunes the camera`() {
        val live = CallSession()
            .on(CallEvent.Asked(Listener, allowed = true, video = true)).first
            .on(CallEvent.LocalOffer("v=0")).first
            .on(CallEvent.AnswerReceived(Listener, "v=0 answer")).first
            .on(CallEvent.Connected).first

        val (session, effects) = live.on(CallEvent.QualityAsked(Listener, CaptureQuality.Low))

        assertEquals(CaptureQuality.Low, session.quality)
        assertEquals(listOf(CallEffect.SetQuality(CaptureQuality.Low)), effects)
    }

    @Test
    fun `a size asked for by a phone outside the call is ignored`() {
        val (_, effects) = CallSession().listening().on(CallEvent.QualityAsked(Other, CaptureQuality.High))

        assertEquals(emptyList<CallEffect>(), effects)
    }

    @Test
    fun `the size belongs to the call, so it goes back to the default when it ends`() {
        val after = CallSession().listening()
            .on(CallEvent.ChooseQuality(CaptureQuality.High)).first
            .on(CallEvent.HangUp).first

        assertEquals(CaptureQuality.Standard, after.quality)
    }

    @Test
    fun `the camera can be started and stopped without touching the call`() {
        val live = CallSession().listening()

        val (on, started) = live.on(CallEvent.AskForVideo(on = true))
        val (off, stopped) = on.on(CallEvent.AskForVideo(on = false))

        assertTrue(on.video)
        assertFalse(off.video)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Video, videoOn)), started)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Video, videoOff)), stopped)

        // The call itself is untouched: no closing, no offering, no new connection.
        assertEquals(live.state, off.state)
    }

    @Test
    fun `only the phone in the call may start this one's camera`() {
        val filming = CallSession()
            .on(CallEvent.Asked(Listener, allowed = true, video = true)).first

        val (_, fromStranger) = filming.on(CallEvent.VideoAsked(Other, on = false))
        val (stopped, fromPeer) = filming.on(CallEvent.VideoAsked(Listener, on = false))

        assertEquals(emptyList<CallEffect>(), fromStranger)
        assertEquals(listOf(CallEffect.SetVideo(false)), fromPeer)
        assertFalse(stopped.video)
    }

    @Test
    fun `asking for the light sends the colour and the brightness`() {
        val (session, effects) = CallSession().listening()
            .on(CallEvent.AskForLight(LightMode.Red, brightness = 0.25f))

        assertEquals(LightState(LightMode.Red, 0.25f), session.light)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Light, dimRed)), effects)
    }

    @Test
    fun `a glance carries an end to itself, a light left on does not`() {
        val (_, glance) = CallSession().listening()
            .on(CallEvent.AskForLight(LightMode.White, brightness = 1f, seconds = GlanceSeconds))
        val (_, staysOn) = CallSession().listening()
            .on(CallEvent.AskForLight(LightMode.White, brightness = 1f))

        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Light, whiteGlance)), glance)
        assertEquals(listOf(CallEffect.Send(Speaker, SignalKinds.Light, whiteSteady)), staysOn)
    }

    @Test
    fun `there is no light to ask for outside a call`() {
        val (_, effects) = CallSession().on(CallEvent.AskForLight(LightMode.White))

        assertEquals(emptyList<CallEffect>(), effects)
    }

    @Test
    fun `the phone in the call is the only one that can light this room`() {
        val live = CallSession().listening()

        val (_, fromStranger) = live.on(CallEvent.LightAsked(Other, LightMode.White, 1f, 8))
        val (session, fromPeer) = live.on(CallEvent.LightAsked(Speaker, LightMode.White, 1f, 8))

        assertEquals(emptyList<CallEffect>(), fromStranger)
        assertEquals(listOf(CallEffect.ShowLight(LightMode.White, 1f, 8)), fromPeer)
        assertEquals(LightState(LightMode.White, 1f), session.light)
    }

    @Test
    fun `hanging up puts the light out before it closes`() {
        val (session, effects) = CallSession().listening().on(CallEvent.HangUp)

        assertEquals(CallState.Idle, session.state)
        assertEquals(
            listOf(
                CallEffect.Send(Speaker, SignalKinds.Light, lightOff),
                CallEffect.Send(Speaker, SignalKinds.Stop, ""),
                CallEffect.Close
            ),
            effects
        )
    }

    @Test
    fun `the other phone hanging up puts this one's light out`() {
        val (_, effects) = CallSession().listening().on(CallEvent.Stopped(Speaker))

        assertEquals(listOf(CallEffect.ShowLight(LightMode.Off, 0f, 0), CallEffect.Close), effects)
    }

    @Test
    fun `a failed call does not leave a nursery lit`() {
        val (_, effects) = CallSession().listening().on(CallEvent.Failure("No route."))

        assertTrue(effects.contains(CallEffect.ShowLight(LightMode.Off, 0f, 0)))
    }

    @Test
    fun `a stop from a phone that is not in the call changes nothing`() {
        val live = CallSession().listening()

        val (session, effects) = live.on(CallEvent.Stopped(Other))

        assertEquals(live.state, session.state)
        assertEquals(emptyList<CallEffect>(), effects)
    }

    @Test
    fun `a failure closes the connection and says why`() {
        val (session, effects) = CallSession().listening().on(CallEvent.Failure("No route."))

        assertEquals(CallState.Failed("No route."), session.state)
        assertEquals(CallEffect.Send(Speaker, SignalKinds.Stop, "No route."), effects.first())
        assertEquals(CallEffect.Close, effects.last())
        assertFalse(session.isBusy)
    }

    /** A call already up, from this phone's side as the one listening. */
    private fun CallSession.listening(): CallSession =
        on(CallEvent.Listen(Speaker)).first
            .on(CallEvent.OfferReceived(Speaker, "v=0")).first
            .on(CallEvent.LocalAnswer("v=0 answer")).first
            .on(CallEvent.Connected).first

    private companion object {
        const val Speaker = "3f1d9b6a-0000-4000-8000-000000000001"
        const val Listener = "3f1d9b6a-0000-4000-8000-000000000002"
        const val Other = "3f1d9b6a-0000-4000-8000-000000000003"
        val CANDIDATE = IceCandidateDto("audio", 0, "candidate:1 1 udp 2130706431 192.168.1.5 50000 typ host")
        val audioOnly: String =
            Json.encodeToString(CallRequest(video = false, quality = CaptureQuality.Standard.name))
        val withVideo: String =
            Json.encodeToString(CallRequest(video = true, quality = CaptureQuality.Standard.name))
        val dimRed: String = Json.encodeToString(LightRequest(LightMode.Red.name, 0.25f, 0))
        val whiteGlance: String =
            Json.encodeToString(LightRequest(LightMode.White.name, 1f, GlanceSeconds))
        val whiteSteady: String = Json.encodeToString(LightRequest(LightMode.White.name, 1f, 0))
        val high: String = Json.encodeToString(QualityRequest(CaptureQuality.High.name))
        val videoOn: String = Json.encodeToString(VideoRequest(on = true))
        val videoOff: String = Json.encodeToString(VideoRequest(on = false))
        val highVideo: String =
            Json.encodeToString(CallRequest(video = true, quality = CaptureQuality.High.name))
        val lightOff: String = Json.encodeToString(LightRequest(LightMode.Off.name))
    }
}

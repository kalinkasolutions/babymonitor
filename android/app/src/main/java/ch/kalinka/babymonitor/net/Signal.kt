package ch.kalinka.babymonitor.net

import kotlinx.serialization.Serializable

/**
 * One message on the way to a media connection. The backend carries these between two phones and
 * reads none of them — [body] is SDP or a candidate line, and the keys that end up protecting the
 * audio are negotiated inside it, between the phones.
 *
 * Every one of them is signed with the sender's identity key and checked against the key this
 * phone confirmed off the other's screen. That is what makes "the server only introduces them"
 * true of a server that is actively lying rather than only of one that is honest: an offer names
 * the DTLS fingerprint the media will be encrypted to, and anything able to rewrite that in
 * flight is a man in the middle. The signature is over the fingerprint too, and the backend
 * cannot produce one — the private half never leaves the keystore.
 *
 * The same message travels both paths, so the WiFi and the hub are protected by one mechanism
 * rather than two that could drift apart.
 */
@Serializable
data class SignalMessage(
    /** Filled in when sending. */
    val toDeviceId: String = "",

    /**
     * Who sent it. Over the hub this is overwritten with the connection the server authenticated,
     * so a sender cannot claim to be somebody else. Across the WiFi there is no server to do that
     * — but lying here only picks the wrong key to check against, so it can cost a sender its own
     * message and can never impersonate anybody.
     */
    val fromDeviceId: String = "",
    val kind: String = "",
    val body: String = "",

    /**
     * When this was signed, as milliseconds since the epoch. Inside the signature, so a message
     * captured in flight is worthless once it has gone stale. That only bounds a replay rather
     * than preventing one — a captured "stop" would still end a call if it came back inside the
     * window — so [SignalReplayGuard] is what makes each of these good exactly once.
     */
    val sentAt: Long = 0,

    /** The sender's signature over [signedBytes], base64. */
    val signature: String = ""
)

/**
 * The bytes both ends sign and check.
 *
 * [fromDeviceId] is deliberately not among them. Verifying against the key pinned for that device
 * is already proof of who sent it, and leaving it out means the hub can go on overwriting the
 * field with the connection it authenticated without invalidating what the sender signed.
 *
 * [toDeviceId] is among them, so a message signed for one phone cannot be handed to another.
 */
fun SignalMessage.signedBytes(): ByteArray =
    listOf(sentAt.toString(), toDeviceId, kind, body).joinToString("\n").toByteArray()

/** What a signal can be; the same closed set the hub accepts. */
object SignalKinds {
    /** "Start sending me audio." From the phone that wants to listen. */
    const val Start = "start"

    /** SDP from the phone that captures, which is the one that offers. */
    const val Offer = "offer"

    /** SDP back from the phone that listens. */
    const val Answer = "answer"

    /** One ICE candidate, either way round. */
    const val Candidate = "candidate"

    /** "I am done", or "I will not" — either way round. */
    const val Stop = "stop"

    /** Turn the light on the phone that is filming on or off. */
    const val Light = "light"

    /** Change how much picture the phone that is filming sends, without dropping the call. */
    const val Quality = "quality"

    /** Start or stop the camera, without dropping the call. */
    const val Video = "video"

    /**
     * "I scanned your screen, and here is who I am." Only ever travels across the LAN, and is the
     * one message accepted from a phone whose key is not yet confirmed — because it is the
     * message that confirms it. The proof inside is what makes that safe.
     */
    const val Pair = "pair"
}

/**
 * What colour the phone in the nursery should make its screen. It has no infrared, so a dark room
 * films as black and the only light available is the one it makes itself.
 */
enum class LightMode {
    /** Off, and the room stays dark. */
    Off,

    /** White. Lights the room properly, and wakes whatever is in it. */
    White,

    /** Red, which a sleeping person notices far less at the same brightness. */
    Red
}

@Serializable
data class LightRequest(
    val mode: String = LightMode.Off.name,

    /** 0 to 1, straight onto the other phone's screen brightness. */
    val brightness: Float = 1f,

    /** How long to stay on. Zero or less stays on until it is turned off. */
    val seconds: Int = 0,

    /**
     * Set when this is an answer rather than an order: the phone that was asked could not light
     * the room, and the phone that asked is the only one with somebody in front of it to read it.
     */
    val reason: String = ""
)

/**
 * What the phone that wants to listen is asking for. Audio always; video only when asked, because
 * it costs battery and bandwidth that a monitor running all night does not have to spare.
 */
@Serializable
data class CallRequest(val video: Boolean = false, val quality: String = "")

/** How much picture to send, asked for while the call is up. */
@Serializable
data class QualityRequest(val quality: String = "")

/** Who scanned, and the proof they were looking at the screen when they did. */
@Serializable
data class PairRequest(
    val deviceId: String = "",
    val name: String = "",
    val publicKey: String = "",
    val proof: String = ""
)

/** Whether the camera should be running. */
@Serializable
data class VideoRequest(val on: Boolean = false)

/** An ICE candidate as it travels: the three fields WebRTC needs to put it back together. */
@Serializable
data class IceCandidateDto(
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = ""
)

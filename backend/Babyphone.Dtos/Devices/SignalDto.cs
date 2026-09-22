namespace Dtos.Devices;

/// <summary>A phone arriving or leaving, as everyone who can see it is told.</summary>
public sealed class DevicePresenceDto
{
    public Guid DeviceId { get; set; }
    public bool IsOnline { get; set; }
}

/// <summary>
/// One message on the way to a media connection: the offer, the answer, the ICE candidates that
/// follow, and the asking and stopping around them.
///
/// The backend carries these and reads none of them. <see cref="Body"/> is opaque here on purpose
/// — it is SDP or a candidate line, the phones' business, and the keys that end up protecting the
/// media are negotiated inside it between the two of them.
/// </summary>
public sealed class SignalDto
{
    /// <summary>The device this is for. Filled in by the sender.</summary>
    public Guid ToDeviceId { get; set; }

    /// <summary>The device it came from. Filled in by the hub, never by the sender.</summary>
    public Guid FromDeviceId { get; set; }

    /// <summary>One of <see cref="SignalKinds"/>.</summary>
    public string Kind { get; set; } = string.Empty;

    public string Body { get; set; } = string.Empty;
}

/// <summary>
/// What a signal can be. A short closed set: anything else is refused at the hub rather than
/// relayed to a phone that would not know what to do with it.
/// </summary>
public static class SignalKinds
{
    /// <summary>"Start sending me audio." From the phone that wants to listen.</summary>
    public const string Start = "start";

    /// <summary>SDP from the phone that captures, which is the one that offers.</summary>
    public const string Offer = "offer";

    /// <summary>SDP back from the phone that listens.</summary>
    public const string Answer = "answer";

    /// <summary>One ICE candidate, either way round.</summary>
    public const string Candidate = "candidate";

    /// <summary>"I am done", or "I could not", either way round.</summary>
    public const string Stop = "stop";

    /// <summary>
    /// Turn the light on the recording phone on or off. A dark room films as black — phones have
    /// no infrared — so the phone in the nursery has to light what it is filming, and the phone
    /// watching is the one that decides when.
    /// </summary>
    public const string Light = "light";

    /// <summary>
    /// How much picture to send, changed while the call is up. The phone watching asks; the phone
    /// filming retunes its camera, which needs no renegotiation and so no interruption.
    /// </summary>
    public const string Quality = "quality";

    /// <summary>
    /// Start or stop the camera while the call is up. The track is negotiated either way, so
    /// turning the picture on and off costs nothing but the camera itself.
    /// </summary>
    public const string Video = "video";

    public static bool IsKnown(string kind) =>
        kind is Start or Offer or Answer or Candidate or Stop or Light or Quality or Video;
}

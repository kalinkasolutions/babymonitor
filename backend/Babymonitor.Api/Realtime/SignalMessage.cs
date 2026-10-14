namespace Babymonitor.Realtime;

/// <summary>
/// One signalling message between two phones. The server reads only the addressing and the kind;
/// the body and signature are checked by the receiving phone, never here.
/// </summary>
public sealed record SignalMessage
{
    public string ToDeviceId { get; init; } = string.Empty;

    /// <summary>Always overwritten with the authenticated sender.</summary>
    public string FromDeviceId { get; init; } = string.Empty;

    public string Kind { get; init; } = string.Empty;

    public string Body { get; init; } = string.Empty;

    /// <summary>Milliseconds since the epoch, covered by the signature.</summary>
    public long SentAt { get; init; }

    public string Signature { get; init; } = string.Empty;
}

public static class SignalKinds
{
    public const string Start = "start";
    public const string Offer = "offer";
    public const string Answer = "answer";
    public const string Candidate = "candidate";
    public const string Stop = "stop";
    public const string Light = "light";
    public const string Quality = "quality";
    public const string Video = "video";

    /// <summary>"I scanned your screen", sent before the receiver trusts the sender's key.</summary>
    public const string Pair = "pair";

    /// <summary>Whether a phone is set up as the monitor, and whether it is in a call.</summary>
    public const string Availability = "availability";

    public static bool IsKnown(string? kind) =>
        kind is Start or Offer or Answer or Candidate or Stop or Light or Quality or Video or Pair or Availability;
}

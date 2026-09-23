namespace Babymonitor.Realtime;

/// <summary>Method names the phones listen for. One place, so they cannot drift or be mistyped.</summary>
public static class DeviceEvents
{
    /// <summary>A device appeared or its state changed; carries the whole device.</summary>
    public const string DeviceChanged = "deviceChanged";

    /// <summary>A device was revoked; carries its id.</summary>
    public const string DeviceRemoved = "deviceRemoved";

    /// <summary>Accounts were linked or unlinked, so the set of visible phones has changed.</summary>
    public const string LinksChanged = "linksChanged";

    /// <summary>
    /// A code was scanned and the pairing finished. Sent to both sides, because the phone that
    /// showed the code is sitting on the pairing screen with no other way to find out.
    /// </summary>
    public const string PairingCompleted = "pairingCompleted";

    /// <summary>
    /// One signalling message on its way to the other phone — offer, answer, candidate. Addressed
    /// to a single device rather than to an account.
    /// </summary>
    public const string Signal = "signal";

    /// <summary>A phone connected or dropped off. Carries its id and whether it is there now.</summary>
    public const string DevicePresence = "devicePresence";
}

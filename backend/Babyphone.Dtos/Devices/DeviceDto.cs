namespace Dtos.Devices;

// A record so one can be re-described for a different reader with `with`: "mine" and "this
// device" are claims about whoever is reading, not about the device.
public sealed record DeviceDto
{
    public Guid Id { get; set; }
    public string Name { get; set; }

    /// <summary>True for the phone that made the request, so the UI can label it.</summary>
    public bool IsThisDevice { get; set; }

    /// <summary>False for a paired phone belonging to someone else, e.g. a babysitter's.</summary>
    public bool IsMine { get; set; }

    /// <summary>
    /// Whether that phone is holding a connection right now. A phone in the list is not the same
    /// thing as a phone that can answer: one is a registration, the other is somebody at home.
    /// </summary>
    public bool IsOnline { get; set; }

    /// <summary>Who owns it, so a phone from another account can be told apart at a glance.</summary>
    public string OwnerName { get; set; }

    public Guid OwnerId { get; set; }

    /// <summary>
    /// A short, human-comparable digest of the public key. Two phones showing the same
    /// fingerprint are talking to each other and not to something in between.
    /// </summary>
    public string KeyFingerprint { get; set; }

    /// <summary>
    /// The device's identity public key as the backend holds it. The other phone compares this
    /// against the key it scanned off this one's screen: if they differ, the value came from
    /// somewhere other than the device, which is exactly the attack the scan exists to catch.
    /// </summary>
    public string PublicKey { get; set; }

    public DateTime? LastSeenAt { get; set; }
    public int? BatteryPercent { get; set; }
    public bool? IsCharging { get; set; }
    public DateTime CreatedAt { get; set; }
}

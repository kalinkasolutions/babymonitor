namespace Entities.Devices;

/// <summary>
/// One phone belonging to an account. Devices on the same account can monitor each other, so
/// registering is what "pairing" amounts to; the QR exchange on top of it proves the key is real.
/// </summary>
public sealed class Device : IBaseEntity
{
    public Guid Id { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime LastUpdatedAt { get; set; }

    public Guid UserId { get; set; }

    /// <summary>What the user calls this phone, defaulting to the model name it reported.</summary>
    public string Name { get; set; }

    /// <summary>
    /// Long-term identity public key, base64. The device generates it once and never sends the
    /// private half; a second device confirms this value out of band by scanning a QR code, which
    /// is what stops the backend from substituting its own and reading the stream.
    /// </summary>
    public string PublicKey { get; set; }

    /// <summary>Last heartbeat. A gap here is what the offline alarm watches for.</summary>
    public DateTime? LastSeenAt { get; set; }

    public int? BatteryPercent { get; set; }
    public bool? IsCharging { get; set; }
}

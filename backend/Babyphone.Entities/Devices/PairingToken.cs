namespace Entities.Devices;

/// <summary>
/// The short-lived secret a phone shows so another can link to its account. Shown two ways: as a
/// QR code, which also carries the device's public key, and as a code short enough to read out
/// and type when there is no camera.
///
/// Redeeming it links the accounts. It never conveys trust — a typed code proves someone was in
/// earshot, not that the key the server later hands over is genuine.
/// </summary>
public sealed class PairingToken : IBaseEntity
{
    public Guid Id { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime LastUpdatedAt { get; set; }

    /// <summary>The device showing the code; its owner is the account being linked to.</summary>
    public Guid DeviceId { get; set; }

    /// <summary>Short and typeable, from an alphabet with no lookalike characters.</summary>
    public string Code { get; set; }

    public DateTime ExpiresAt { get; set; }

    /// <summary>Set when redeemed; a code is good for exactly one link.</summary>
    public DateTime? UsedAt { get; set; }
}

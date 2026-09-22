namespace Dtos.Devices;

/// <summary>
/// What a phone shows so another can link to it. The QR carries all of this; the short code on
/// its own carries only <see cref="Code"/>, which is why typing it links the accounts but leaves
/// the key still to be compared.
/// </summary>
public sealed class PairingCodeDto
{
    public Guid DeviceId { get; set; }
    public string PublicKey { get; set; }

    /// <summary>Eight characters, grouped in fours, from an alphabet with no lookalikes.</summary>
    public string Code { get; set; }

    public DateTime ExpiresAt { get; set; }
}

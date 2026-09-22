namespace Dtos.Devices;

/// <summary>
/// Announced when a code is claimed. Carries what the phone that showed the code needs in order
/// to trust the phone that scanned it, without either having to ask the backend to vouch.
/// </summary>
public sealed class PairingCompletedDto
{
    /// <summary>Which phone's code was claimed; only that one can check the proof.</summary>
    public Guid ShowingDeviceId { get; set; }

    /// <summary>The phone that did the scanning, including the key it claims to hold.</summary>
    public DeviceDto ClaimingDevice { get; set; }

    /// <summary>
    /// The scanner's signature over its own identity. Empty when the code was typed, in which
    /// case nothing here can be confirmed and both sides stay at the lower trust level.
    /// </summary>
    public string Proof { get; set; }
}

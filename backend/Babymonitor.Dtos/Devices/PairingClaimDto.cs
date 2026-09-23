namespace Dtos.Devices;

/// <summary>
/// The result of redeeming a code, seen from both ends: what the scanner gets back, and what the
/// phone that showed the code has to be told. The two phones are told apart deliberately — each
/// device is described from the point of view of the account that will read it.
/// </summary>
public sealed class PairingClaimDto
{
    /// <summary>The phone that showed the code, as the account that scanned it sees it.</summary>
    public DeviceDto ShowingDevice { get; set; }

    /// <summary>The phone that scanned, as the account that showed the code sees it.</summary>
    public DeviceDto ClaimingDevice { get; set; }

    /// <summary>The scanner's proof that it read the code off a screen. Empty when it was typed.</summary>
    public string Proof { get; set; }
}

using System.ComponentModel.DataAnnotations;

namespace Dtos.Devices;

public sealed class ClaimPairingDto
{
    /// <summary>The code read off the other phone's screen, scanned or typed.</summary>
    [Required]
    [MaxLength(32)]
    public string Code { get; set; }

    /// <summary>
    /// Proof that this phone saw the other one's screen: a signature over its own identity, made
    /// with a secret that was only ever in the QR code. Relayed untouched to the phone that showed
    /// the code, which is the only other party that knows the secret and can check it.
    ///
    /// Absent when the code was typed rather than scanned — a code short enough to read out cannot
    /// carry the secret, so that path links without confirming anything.
    /// </summary>
    [MaxLength(256)]
    public string Proof { get; set; }
}

namespace Dtos.Account;

/// <summary>
/// The shared secret for a pending authenticator setup. Handed out while two-factor is still off —
/// it only becomes the account's second factor once a generated code is verified.
/// </summary>
public sealed class TwoFactorSetupDto
{
    /// <summary>The secret in groups of four, for typing into an app by hand.</summary>
    public string SharedKey { get; set; }

    /// <summary>The <c>otpauth://</c> URI to render as a QR code.</summary>
    public string AuthenticatorUri { get; set; }
}

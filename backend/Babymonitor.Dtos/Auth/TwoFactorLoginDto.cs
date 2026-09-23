using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class TwoFactorLoginDto
{
    /// <summary>A six-digit authenticator code, or one of the account's recovery codes.</summary>
    [Required]
    public string Code { get; set; }

    /// <summary>Skip the second step on this device next time.</summary>
    public bool RememberMachine { get; set; }
}

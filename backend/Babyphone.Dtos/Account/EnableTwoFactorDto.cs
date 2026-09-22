using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class EnableTwoFactorDto
{
    /// <summary>A current six-digit code from the authenticator app, proving the secret was stored.</summary>
    [Required]
    public string Code { get; set; }
}

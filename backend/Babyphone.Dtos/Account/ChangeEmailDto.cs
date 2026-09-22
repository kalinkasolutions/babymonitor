using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class ChangeEmailDto
{
    [Required]
    [EmailAddress]
    [MaxLength(256)]
    public string Email { get; set; }

    /// <summary>Confirms the account owner is present, since the address is also the sign-in identifier.</summary>
    [Required]
    public string CurrentPassword { get; set; }
}

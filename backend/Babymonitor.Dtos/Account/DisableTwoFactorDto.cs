using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class DisableTwoFactorDto
{
    /// <summary>The account password; turning the second factor off is a security downgrade.</summary>
    [Required]
    public string CurrentPassword { get; set; }
}

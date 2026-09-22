using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class DeleteAccountDto
{
    /// <summary>The account password, to prove the owner is the one deleting it.</summary>
    [Required]
    public string CurrentPassword { get; set; }
}

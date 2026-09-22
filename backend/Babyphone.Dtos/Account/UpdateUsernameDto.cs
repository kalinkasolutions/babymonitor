using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class UpdateUsernameDto
{
    /// <summary>The new display name. Not an identifier and need not be unique.</summary>
    [Required]
    [MaxLength(256)]
    public string Username { get; set; }
}

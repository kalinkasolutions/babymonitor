using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class LoginDto
{
    /// <summary>Email address of the account to sign in to; the username is a display name only.</summary>
    [Required]
    public string Email { get; set; }

    [Required]
    public string Password { get; set; }
}

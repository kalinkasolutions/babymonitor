using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class RegisterUserDto
{
    [Required]
    [EmailAddress]
    [MaxLength(256)]
    public string Email { get; set; }

    [Required]
    [MaxLength(256)]
    public string Username { get; set; }

    [Required]
    public string Password { get; set; }
}

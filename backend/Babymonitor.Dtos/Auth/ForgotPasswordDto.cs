using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class ForgotPasswordDto
{
    [Required]
    [EmailAddress]
    public string Email { get; set; }
}

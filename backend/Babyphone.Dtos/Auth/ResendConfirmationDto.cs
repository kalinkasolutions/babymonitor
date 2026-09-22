using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class ResendConfirmationDto
{
    [Required]
    [EmailAddress]
    public string Email { get; set; }
}

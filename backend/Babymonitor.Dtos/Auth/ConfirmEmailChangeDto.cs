using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class ConfirmEmailChangeDto
{
    [Required]
    public Guid UserId { get; set; }

    [Required]
    [EmailAddress]
    public string Email { get; set; }

    [Required]
    public string Token { get; set; }
}

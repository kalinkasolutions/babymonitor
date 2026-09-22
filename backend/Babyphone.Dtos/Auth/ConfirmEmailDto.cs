using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

public sealed class ConfirmEmailDto
{
    [Required]
    public Guid UserId { get; set; }

    [Required]
    public string Token { get; set; }
}

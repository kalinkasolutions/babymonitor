using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class ChangePasswordDto
{
    [Required]
    public string CurrentPassword { get; set; }

    [Required]
    [MinLength(4)]
    public string NewPassword { get; set; }
}

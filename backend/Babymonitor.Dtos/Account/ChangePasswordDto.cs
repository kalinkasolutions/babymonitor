using System.ComponentModel.DataAnnotations;

namespace Dtos.Account;

public sealed class ChangePasswordDto
{
    [Required]
    public string CurrentPassword { get; set; }

    [Required]
    public string NewPassword { get; set; }
}

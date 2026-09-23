using System.ComponentModel.DataAnnotations;

namespace Dtos.Auth;

/// <summary>
/// Invites someone who is not in the room to join — the alternative to pairing by QR code.
/// </summary>
public sealed class InviteUserDto
{
    [Required]
    [EmailAddress]
    [MaxLength(256)]
    public string Email { get; set; }

    [Required]
    [MaxLength(256)]
    public string Username { get; set; }
}

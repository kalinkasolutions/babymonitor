namespace Dtos.Account;

/// <summary>Everything the account screen shows about the signed-in user.</summary>
public sealed class AccountDto
{
    public Guid UserId { get; set; }

    /// <summary>Display name shown on the other phone; not used to sign in.</summary>
    public string Username { get; set; }

    /// <summary>The address used to sign in, to invite this user and to reset their password.</summary>
    public string Email { get; set; }

    public bool EmailConfirmed { get; set; }
    public bool TwoFactorEnabled { get; set; }

    /// <summary>Number of unused recovery codes left; 0 while two-factor is off.</summary>
    public int RecoveryCodesLeft { get; set; }

    public DateTime MemberSince { get; set; }
}

using Shared;

namespace Babyphone.BusinessLogic.Email;

public interface IEmailService
{
    /// <summary>Sends the account email-confirmation link for a freshly registered user.</summary>
    Task<OperationResult<Empty>> SendConfirmationMailAsync(string recipient, Guid userId, string token);

    /// <summary>Sends the password-reset link for a user who requested it.</summary>
    Task<OperationResult<Empty>> SendResetPasswordMailAsync(string recipient, string token);

    /// <summary>
    /// Sends the link an invited account uses to set its first password. The link target is the
    /// reset-password screen — the token is a reset token — only the wording differs, because the
    /// recipient never asked for anything.
    /// </summary>
    Task<OperationResult<Empty>> SendInviteMailAsync(string recipient, string invitedBy, string token);

    /// <summary>
    /// Sends the link an existing account follows to accept being linked to another one. The
    /// recipient decides, not the inviter: an account that already exists belongs to somebody
    /// else, and knowing their address is not consent to see their phones.
    /// </summary>
    Task<OperationResult<Empty>> SendAccountLinkMailAsync(
        string recipient, string invitedBy, Guid userId, Guid inviterId, string token);

    /// <summary>
    /// Sends the confirmation link for an email change to the <em>new</em> address, so the account
    /// only moves once the user proves they can read mail there.
    /// </summary>
    Task<OperationResult<Empty>> SendChangeEmailMailAsync(string newEmail, Guid userId, string token);
}

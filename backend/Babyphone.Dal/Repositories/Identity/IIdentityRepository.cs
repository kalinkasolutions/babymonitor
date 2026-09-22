using Entities.Account;
using Shared;

namespace Dal.Repositories.Identity;

public interface IIdentityRepository
{
    /// <summary>Creates the user; on success the created entity (with its generated id) is returned.</summary>
    Task<OperationResult<BabyphoneUser>> RegisterUserAsync(BabyphoneUser identityUser, string password);

    /// <summary>Creates a user with no password, for an invitation that sets one later.</summary>
    Task<OperationResult<BabyphoneUser>> CreateInvitedUserAsync(BabyphoneUser identityUser);

    /// <summary>Adds the roles the user does not already hold. Missing roles are reported, not thrown.</summary>
    Task<OperationResult<Empty>> AddToRolesAsync(BabyphoneUser user, params string[] roles);

    Task<BabyphoneUser?> FindByEmailAsync(string email);
    Task<BabyphoneUser?> FindByIdAsync(Guid userId);

    Task<string> GenerateEmailConfirmationTokenAsync(BabyphoneUser user);
    Task<OperationResult<Empty>> ConfirmEmailAsync(BabyphoneUser user, string token);

    /// <summary>
    /// Redeems a change-email token: moves the account to <paramref name="newEmail"/> and marks it
    /// confirmed. The token is generated in the account layer when the user requests the change.
    /// </summary>
    Task<OperationResult<Empty>> ChangeEmailAsync(BabyphoneUser user, string newEmail, string token);

    /// <summary>
    /// A token that lets <paramref name="user"/> accept a link to <paramref name="inviterId"/>'s
    /// account. Bound to both, so it cannot be replayed to link someone else, and short-lived.
    /// </summary>
    Task<string> GenerateAccountLinkTokenAsync(BabyphoneUser user, Guid inviterId);

    Task<bool> VerifyAccountLinkTokenAsync(BabyphoneUser user, Guid inviterId, string token);

    Task<string> GeneratePasswordResetTokenAsync(BabyphoneUser user);
    Task<OperationResult<Empty>> ResetPasswordAsync(BabyphoneUser user, string token, string newPassword);
}

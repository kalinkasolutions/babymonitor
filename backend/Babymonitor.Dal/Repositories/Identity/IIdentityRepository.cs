using Entities.Account;
using Shared;

namespace Dal.Repositories.Identity;

public interface IIdentityRepository
{
    /// <summary>Creates the user; on success the created entity (with its generated id) is returned.</summary>
    Task<OperationResult<BabymonitorUser>> RegisterUserAsync(BabymonitorUser identityUser, string password);

    /// <summary>Creates a user with no password, for an invitation that sets one later.</summary>
    Task<OperationResult<BabymonitorUser>> CreateInvitedUserAsync(BabymonitorUser identityUser);

    /// <summary>Adds the roles the user does not already hold. Missing roles are reported, not thrown.</summary>
    Task<OperationResult<Empty>> AddToRolesAsync(BabymonitorUser user, params string[] roles);

    Task<BabymonitorUser?> FindByEmailAsync(string email);
    Task<BabymonitorUser?> FindByIdAsync(Guid userId);

    Task<string> GenerateEmailConfirmationTokenAsync(BabymonitorUser user);
    Task<OperationResult<Empty>> ConfirmEmailAsync(BabymonitorUser user, string token);

    /// <summary>
    /// Redeems a change-email token: moves the account to <paramref name="newEmail"/> and marks it
    /// confirmed. The token is generated in the account layer when the user requests the change.
    /// </summary>
    Task<OperationResult<Empty>> ChangeEmailAsync(BabymonitorUser user, string newEmail, string token);

    /// <summary>
    /// A token that lets <paramref name="user"/> accept a link to <paramref name="inviterId"/>'s
    /// account. Bound to both, so it cannot be replayed to link someone else, and short-lived.
    /// </summary>
    Task<string> GenerateAccountLinkTokenAsync(BabymonitorUser user, Guid inviterId);

    Task<bool> VerifyAccountLinkTokenAsync(BabymonitorUser user, Guid inviterId, string token);

    Task<string> GeneratePasswordResetTokenAsync(BabymonitorUser user);
    Task<OperationResult<Empty>> ResetPasswordAsync(BabymonitorUser user, string token, string newPassword);
}

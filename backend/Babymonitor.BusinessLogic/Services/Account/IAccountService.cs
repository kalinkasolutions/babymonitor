using Dtos.Account;
using Shared;

namespace Babymonitor.BusinessLogic.Services.Account;

/// <summary>
/// Self-service for the signed-in user's own account. Every method takes the caller's id from the
/// endpoint, so there is no cross-user surface here: a user can only ever read or change themselves.
/// </summary>
public interface IAccountService
{
    Task<OperationResult<AccountDto>> GetAsync(Guid userId);

    /// <summary>Changes the display name the other phone sees.</summary>
    Task<OperationResult<AccountDto>> UpdateUsernameAsync(Guid userId, UpdateUsernameDto dto);

    Task<OperationResult<Empty>> ChangePasswordAsync(Guid userId, ChangePasswordDto dto);

    /// <summary>
    /// Starts an email change by mailing a confirmation link to the new address. The account keeps
    /// its current address until that link is followed.
    /// </summary>
    Task<OperationResult<Empty>> RequestEmailChangeAsync(Guid userId, ChangeEmailDto dto);

    /// <summary>Invalidates every auth cookie issued to this user, on all their devices.</summary>
    Task<OperationResult<Empty>> SignOutEverywhereAsync(Guid userId);

    /// <summary>The authenticator secret to scan or type, for a user who does not have 2FA on yet.</summary>
    Task<OperationResult<TwoFactorSetupDto>> GetTwoFactorSetupAsync(Guid userId);

    /// <summary>Verifies a code against the pending secret and, if it matches, turns 2FA on and issues recovery codes.</summary>
    Task<OperationResult<RecoveryCodesDto>> EnableTwoFactorAsync(Guid userId, EnableTwoFactorDto dto);

    /// <summary>Turns 2FA off and discards the authenticator secret, so re-enabling starts from a new one.</summary>
    Task<OperationResult<Empty>> DisableTwoFactorAsync(Guid userId, DisableTwoFactorDto dto);

    /// <summary>Replaces the remaining recovery codes with a fresh set.</summary>
    Task<OperationResult<RecoveryCodesDto>> RegenerateRecoveryCodesAsync(Guid userId, DisableTwoFactorDto dto);

    /// <summary>Deletes the account and everything that belongs to it.</summary>
    Task<OperationResult<Empty>> DeleteAsync(Guid userId, DeleteAccountDto dto);
}

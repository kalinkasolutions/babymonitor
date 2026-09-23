using Entities.Account;
using Shared;

namespace Dal.Repositories.Account;

public interface IAccountRepository
{
    Task<BabymonitorUser?> FindByIdAsync(Guid userId);
    Task<BabymonitorUser?> FindByEmailAsync(string email);

    Task<OperationResult<Empty>> SetUsernameAsync(BabymonitorUser user, string username);

    /// <summary>Token for moving the account to <paramref name="newEmail"/>, redeemed by the link mailed there.</summary>
    Task<string> GenerateChangeEmailTokenAsync(BabymonitorUser user, string newEmail);

    /// <summary>Rotates the security stamp, which invalidates every auth cookie issued to this user.</summary>
    Task UpdateSecurityStampAsync(BabymonitorUser user);

    Task<OperationResult<Empty>> DeleteAsync(BabymonitorUser user);

    Task<bool> CheckPasswordAsync(BabymonitorUser user, string password);
    Task<OperationResult<Empty>> ChangePasswordAsync(BabymonitorUser user, string currentPassword, string newPassword);

    Task<string?> GetAuthenticatorKeyAsync(BabymonitorUser user);

    /// <summary>Generates a fresh authenticator secret (discarding any previous one) and returns it.</summary>
    Task<string> ResetAuthenticatorKeyAsync(BabymonitorUser user);

    /// <summary>Whether <paramref name="code"/> is currently valid for the user's authenticator secret.</summary>
    Task<bool> VerifyAuthenticatorCodeAsync(BabymonitorUser user, string code);

    Task<OperationResult<Empty>> SetTwoFactorEnabledAsync(BabymonitorUser user, bool enabled);

    /// <summary>Replaces the user's recovery codes and returns the new plaintext set (only ever available here).</summary>
    Task<List<string>> GenerateRecoveryCodesAsync(BabymonitorUser user, int count);

    Task<int> CountRecoveryCodesAsync(BabymonitorUser user);
}

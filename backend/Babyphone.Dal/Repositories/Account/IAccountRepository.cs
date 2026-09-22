using Entities.Account;
using Shared;

namespace Dal.Repositories.Account;

public interface IAccountRepository
{
    Task<BabyphoneUser?> FindByIdAsync(Guid userId);
    Task<BabyphoneUser?> FindByEmailAsync(string email);

    Task<OperationResult<Empty>> SetUsernameAsync(BabyphoneUser user, string username);

    /// <summary>Token for moving the account to <paramref name="newEmail"/>, redeemed by the link mailed there.</summary>
    Task<string> GenerateChangeEmailTokenAsync(BabyphoneUser user, string newEmail);

    /// <summary>Rotates the security stamp, which invalidates every auth cookie issued to this user.</summary>
    Task UpdateSecurityStampAsync(BabyphoneUser user);

    Task<OperationResult<Empty>> DeleteAsync(BabyphoneUser user);

    Task<bool> CheckPasswordAsync(BabyphoneUser user, string password);
    Task<OperationResult<Empty>> ChangePasswordAsync(BabyphoneUser user, string currentPassword, string newPassword);

    Task<string?> GetAuthenticatorKeyAsync(BabyphoneUser user);

    /// <summary>Generates a fresh authenticator secret (discarding any previous one) and returns it.</summary>
    Task<string> ResetAuthenticatorKeyAsync(BabyphoneUser user);

    /// <summary>Whether <paramref name="code"/> is currently valid for the user's authenticator secret.</summary>
    Task<bool> VerifyAuthenticatorCodeAsync(BabyphoneUser user, string code);

    Task<OperationResult<Empty>> SetTwoFactorEnabledAsync(BabyphoneUser user, bool enabled);

    /// <summary>Replaces the user's recovery codes and returns the new plaintext set (only ever available here).</summary>
    Task<List<string>> GenerateRecoveryCodesAsync(BabyphoneUser user, int count);

    Task<int> CountRecoveryCodesAsync(BabyphoneUser user);
}

using Dal.Extensions;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Shared;

namespace Dal.Repositories.Account;

public sealed class AccountRepository : IAccountRepository
{
    private readonly UserManager<BabyphoneUser> m_userManager;

    public AccountRepository(UserManager<BabyphoneUser> userManager)
    {
        m_userManager = userManager;
    }

    public Task<BabyphoneUser?> FindByIdAsync(Guid userId)
    {
        return m_userManager.FindByIdAsync(userId.ToString());
    }

    public Task<BabyphoneUser?> FindByEmailAsync(string email)
    {
        return m_userManager.FindByEmailAsync(email);
    }

    public async Task<OperationResult<Empty>> SetUsernameAsync(BabyphoneUser user, string username)
    {
        var result = await m_userManager.SetUserNameAsync(user, username);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }

    public Task<string> GenerateChangeEmailTokenAsync(BabyphoneUser user, string newEmail)
    {
        return m_userManager.GenerateChangeEmailTokenAsync(user, newEmail);
    }

    public Task UpdateSecurityStampAsync(BabyphoneUser user)
    {
        return m_userManager.UpdateSecurityStampAsync(user);
    }

    public async Task<OperationResult<Empty>> DeleteAsync(BabyphoneUser user)
    {
        var result = await m_userManager.DeleteAsync(user);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.Error(result.ToErrorString());
    }

    public Task<bool> CheckPasswordAsync(BabyphoneUser user, string password)
    {
        return m_userManager.CheckPasswordAsync(user, password);
    }

    public async Task<OperationResult<Empty>> ChangePasswordAsync(
        BabyphoneUser user, string currentPassword, string newPassword)
    {
        var result = await m_userManager.ChangePasswordAsync(user, currentPassword, newPassword);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }

    public Task<string?> GetAuthenticatorKeyAsync(BabyphoneUser user)
    {
        return m_userManager.GetAuthenticatorKeyAsync(user);
    }

    public async Task<string> ResetAuthenticatorKeyAsync(BabyphoneUser user)
    {
        await m_userManager.ResetAuthenticatorKeyAsync(user);
        return await m_userManager.GetAuthenticatorKeyAsync(user) ?? string.Empty;
    }

    public Task<bool> VerifyAuthenticatorCodeAsync(BabyphoneUser user, string code)
    {
        return m_userManager.VerifyTwoFactorTokenAsync(
            user, m_userManager.Options.Tokens.AuthenticatorTokenProvider, code);
    }

    public async Task<OperationResult<Empty>> SetTwoFactorEnabledAsync(BabyphoneUser user, bool enabled)
    {
        var result = await m_userManager.SetTwoFactorEnabledAsync(user, enabled);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }

    public async Task<List<string>> GenerateRecoveryCodesAsync(BabyphoneUser user, int count)
    {
        var codes = await m_userManager.GenerateNewTwoFactorRecoveryCodesAsync(user, count);
        return codes?.ToList() ?? [];
    }

    public Task<int> CountRecoveryCodesAsync(BabyphoneUser user)
    {
        return m_userManager.CountRecoveryCodesAsync(user);
    }
}

using Dal.Extensions;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Shared;

namespace Dal.Repositories.Identity;

public sealed class IdentityRepository : IIdentityRepository
{
    private readonly UserManager<BabyphoneUser> m_userManager;

    public IdentityRepository(
        UserManager<BabyphoneUser> userManager
    )
    {
        m_userManager = userManager;
    }

    public async Task<OperationResult<BabyphoneUser>> RegisterUserAsync(BabyphoneUser identityUser, string password)
    {
        var createUserResult = await m_userManager.CreateAsync(identityUser, password);
        if (!createUserResult.Succeeded)
        {
            return OperationResult<BabyphoneUser>.BadRequest(createUserResult.ToErrorString());
        }

        return OperationResult<BabyphoneUser>.Success(identityUser);
    }

    public async Task<OperationResult<BabyphoneUser>> CreateInvitedUserAsync(BabyphoneUser identityUser)
    {
        var createUserResult = await m_userManager.CreateAsync(identityUser);
        if (!createUserResult.Succeeded)
        {
            return OperationResult<BabyphoneUser>.BadRequest(createUserResult.ToErrorString());
        }

        return OperationResult<BabyphoneUser>.Success(identityUser);
    }

    public async Task<OperationResult<Empty>> AddToRolesAsync(BabyphoneUser user, params string[] roles)
    {
        var existing = await m_userManager.GetRolesAsync(user);
        var missing = roles.Except(existing, StringComparer.Ordinal).ToArray();
        if (missing.Length == 0)
        {
            return OperationResult<Empty>.Success();
        }

        var result = await m_userManager.AddToRolesAsync(user, missing);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.Error(result.ToErrorString());
    }

    public Task<BabyphoneUser?> FindByEmailAsync(string email)
    {
        return m_userManager.FindByEmailAsync(email);
    }

    public Task<BabyphoneUser?> FindByIdAsync(Guid userId)
    {
        return m_userManager.FindByIdAsync(userId.ToString());
    }

    public Task<string> GenerateEmailConfirmationTokenAsync(BabyphoneUser user)
    {
        return m_userManager.GenerateEmailConfirmationTokenAsync(user);
    }

    public async Task<OperationResult<Empty>> ConfirmEmailAsync(BabyphoneUser user, string token)
    {
        var result = await m_userManager.ConfirmEmailAsync(user, token);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }

    public async Task<OperationResult<Empty>> ChangeEmailAsync(BabyphoneUser user, string newEmail, string token)
    {
        var result = await m_userManager.ChangeEmailAsync(user, newEmail, token);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }

    public Task<string> GenerateAccountLinkTokenAsync(BabyphoneUser user, Guid inviterId)
    {
        return m_userManager.GenerateUserTokenAsync(user, TokenOptions.DefaultProvider, LinkPurpose(inviterId));
    }

    public Task<bool> VerifyAccountLinkTokenAsync(BabyphoneUser user, Guid inviterId, string token)
    {
        return m_userManager.VerifyUserTokenAsync(user, TokenOptions.DefaultProvider, LinkPurpose(inviterId), token);
    }

    // The inviter is part of what the token is signed over, so a link mailed to one person cannot
    // be turned into a link to somebody else's account.
    private static string LinkPurpose(Guid inviterId) => "account-link:" + inviterId;

    public Task<string> GeneratePasswordResetTokenAsync(BabyphoneUser user)
    {
        return m_userManager.GeneratePasswordResetTokenAsync(user);
    }

    public async Task<OperationResult<Empty>> ResetPasswordAsync(BabyphoneUser user, string token, string newPassword)
    {
        var result = await m_userManager.ResetPasswordAsync(user, token, newPassword);
        return result.Succeeded
            ? OperationResult<Empty>.Success()
            : OperationResult<Empty>.BadRequest(result.ToErrorString());
    }
}

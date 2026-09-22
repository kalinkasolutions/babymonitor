using System.Security.Claims;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Shared;

namespace Babyphone.Extensions;

public static class SignInRefreshExtension
{
    /// <summary>
    /// Converts an account-change result to an HTTP result, re-issuing the caller's auth cookie first
    /// when the change succeeded. Identity rotates a user's security stamp when their password,
    /// username, authenticator secret or two-factor state changes, and that invalidates every cookie
    /// issued to them — including the one on the device making the request. Refreshing the sign-in
    /// hands this device a cookie carrying the new stamp, while other devices stay signed out.
    /// </summary>
    public static async Task<IResult> ToRefreshedHttpResultAsync<T>(
        this OperationResult<T> operationResult,
        SignInManager<BabyphoneUser> signInManager,
        ClaimsPrincipal principal
    )
    {
        if (operationResult.IsSuccess)
        {
            var user = await signInManager.UserManager.GetUserAsync(principal);
            if (user is not null)
            {
                await signInManager.RefreshSignInAsync(user);
            }
        }

        return operationResult.ToHttpResult();
    }
}

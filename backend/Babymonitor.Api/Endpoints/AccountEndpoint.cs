using Babymonitor.BusinessLogic.Services.Account;
using Babymonitor.Extensions;
using Dtos.Account;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;

namespace Babymonitor.Endpoints;

public static class AccountEndpoint
{
    public static void MapAccountEndpoint(this IEndpointRouteBuilder builder)
    {
        var group = builder.MapGroup("api/account").RequireAuthorization();
        group.MapGet("", GetAsync);
        group.MapPut("username", UpdateUsernameAsync);
        group.MapPost("password", ChangePasswordAsync);
        group.MapPost("email", RequestEmailChangeAsync);
        group.MapPost("sign-out-everywhere", SignOutEverywhereAsync);
        group.MapGet("2fa/setup", GetTwoFactorSetupAsync);
        group.MapPost("2fa/enable", EnableTwoFactorAsync);
        group.MapPost("2fa/disable", DisableTwoFactorAsync);
        group.MapPost("2fa/recovery-codes", RegenerateRecoveryCodesAsync);
        group.MapDelete("", DeleteAsync);
    }

    private static async Task<IResult> GetAsync(IAccountService accountService, HttpContext httpContext)
    {
        return (await accountService.GetAsync(httpContext.User.GetUserId())).ToHttpResult();
    }

    private static async Task<IResult> UpdateUsernameAsync(
        UpdateUsernameDto dto,
        IAccountService accountService,
        SignInManager<BabymonitorUser> signInManager,
        HttpContext httpContext)
    {
        var result = await accountService.UpdateUsernameAsync(httpContext.User.GetUserId(), dto);
        return await result.ToRefreshedHttpResultAsync(signInManager, httpContext.User);
    }

    private static async Task<IResult> ChangePasswordAsync(
        ChangePasswordDto dto,
        IAccountService accountService,
        SignInManager<BabymonitorUser> signInManager,
        HttpContext httpContext)
    {
        var result = await accountService.ChangePasswordAsync(httpContext.User.GetUserId(), dto);
        return await result.ToRefreshedHttpResultAsync(signInManager, httpContext.User);
    }

    private static async Task<IResult> RequestEmailChangeAsync(
        ChangeEmailDto dto, IAccountService accountService, HttpContext httpContext)
    {
        return (await accountService.RequestEmailChangeAsync(httpContext.User.GetUserId(), dto)).ToHttpResult();
    }

    /// <summary>
    /// Rotating the stamp signs this device out too, so the cookie is deliberately not refreshed —
    /// "everywhere" has to include the phone you pressed the button on.
    /// </summary>
    private static async Task<IResult> SignOutEverywhereAsync(
        IAccountService accountService, HttpContext httpContext)
    {
        return (await accountService.SignOutEverywhereAsync(httpContext.User.GetUserId())).ToHttpResult();
    }

    private static async Task<IResult> GetTwoFactorSetupAsync(IAccountService accountService, HttpContext httpContext)
    {
        return (await accountService.GetTwoFactorSetupAsync(httpContext.User.GetUserId())).ToHttpResult();
    }

    private static async Task<IResult> EnableTwoFactorAsync(
        EnableTwoFactorDto dto,
        IAccountService accountService,
        SignInManager<BabymonitorUser> signInManager,
        HttpContext httpContext)
    {
        var result = await accountService.EnableTwoFactorAsync(httpContext.User.GetUserId(), dto);
        return await result.ToRefreshedHttpResultAsync(signInManager, httpContext.User);
    }

    private static async Task<IResult> DisableTwoFactorAsync(
        DisableTwoFactorDto dto,
        IAccountService accountService,
        SignInManager<BabymonitorUser> signInManager,
        HttpContext httpContext)
    {
        var result = await accountService.DisableTwoFactorAsync(httpContext.User.GetUserId(), dto);
        return await result.ToRefreshedHttpResultAsync(signInManager, httpContext.User);
    }

    private static async Task<IResult> RegenerateRecoveryCodesAsync(
        DisableTwoFactorDto dto, IAccountService accountService, HttpContext httpContext)
    {
        return (await accountService.RegenerateRecoveryCodesAsync(httpContext.User.GetUserId(), dto))
            .ToHttpResult();
    }

    private static async Task<IResult> DeleteAsync(
        // Explicit: a DELETE body is not inferred, and without this the app fails at startup.
        [FromBody] DeleteAccountDto dto,
        IAccountService accountService,
        SignInManager<BabymonitorUser> signInManager,
        HttpContext httpContext)
    {
        var result = await accountService.DeleteAsync(httpContext.User.GetUserId(), dto);
        if (result.IsSuccess)
        {
            await signInManager.SignOutAsync();
        }

        return result.ToHttpResult();
    }
}

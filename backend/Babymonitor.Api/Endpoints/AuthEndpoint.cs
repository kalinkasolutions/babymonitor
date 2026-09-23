using Babymonitor.BusinessLogic.Services.Identity;
using Babymonitor.Extensions;
using Babymonitor.Realtime;
using Dtos.Auth;
using Entities.Account;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;
using Shared;

namespace Babymonitor.Endpoints;

public static class AuthEndpoint
{
    private const string LogCategory = "Babymonitor.Endpoints.Auth";

    /// <summary>Same reply for an unknown address and a wrong password, so neither can be probed.</summary>
    private const string BadCredentials = "Bad email or password.";

    /// <summary>
    /// Throttles the endpoints an outsider can reach without a cookie: guessing passwords, making
    /// accounts, and the two that send somebody an email. Lockout already protects one account
    /// from a run of guesses; this is what protects every account from a run against all of them.
    /// </summary>
    public const string CredentialPolicy = "auth-credentials";

    /// <summary>Throttles invitations, which create an account and send mail to an address the caller picked.</summary>
    public const string InvitePolicy = "auth-invite";

    public static void MapAuthEndpoint(this IEndpointRouteBuilder builder)
    {
        var group = builder.MapGroup("api/auth");
        group.MapPost("register", RegisterUserAsync).RequireRateLimiting(CredentialPolicy);
        group.MapPost("login", LoginAsync).RequireRateLimiting(CredentialPolicy);
        group.MapPost("login-2fa", LoginTwoFactorAsync).RequireRateLimiting(CredentialPolicy);
        group.MapGet("status", GetAuthStatus);
        group.MapPost("logout", LogoutAsync);

        group.MapPost("resend-confirmation", ResendConfirmationAsync).RequireRateLimiting(CredentialPolicy);
        group.MapPost("forgot-password", ForgotPasswordAsync).RequireRateLimiting(CredentialPolicy);
        group.MapPost("reset-password", ResetPasswordAsync).RequireRateLimiting(CredentialPolicy);
        group.MapPost("invite", InviteUserAsync).RequireAuthorization().RequireRateLimiting(InvitePolicy);

        // Followed from an email rather than called by the app, so these answer with a page.
        group.MapGet("confirm-email", ConfirmEmailPageAsync);
        group.MapGet("confirm-email-change", ConfirmEmailChangePageAsync);
        group.MapGet("accept-link", ConfirmAccountLinkPageAsync);
        group.MapPost("accept-link", AcceptAccountLinkAsync).DisableAntiforgery();
        group.MapGet("reset-password-form", ResetPasswordFormPage);
        group.MapPost("reset-password-form", ResetPasswordFormAsync).DisableAntiforgery();
    }

    private static async Task<IResult> RegisterUserAsync(RegisterUserDto registerUserDto, IIdentityService identityService)
    {
        return (await identityService.RegisterUserAsync(registerUserDto)).ToHttpResult();
    }

    private static async Task<IResult> ResendConfirmationAsync(
        ResendConfirmationDto resendConfirmationDto, IIdentityService identityService)
    {
        return (await identityService.ResendConfirmationAsync(resendConfirmationDto)).ToHttpResult();
    }

    private static async Task<IResult> ForgotPasswordAsync(
        ForgotPasswordDto forgotPasswordDto, IIdentityService identityService)
    {
        return (await identityService.SendPasswordResetAsync(forgotPasswordDto)).ToHttpResult();
    }

    private static async Task<IResult> ResetPasswordAsync(
        ResetPasswordDto resetPasswordDto, IIdentityService identityService)
    {
        return (await identityService.ResetPasswordAsync(resetPasswordDto)).ToHttpResult();
    }

    private static async Task<IResult> InviteUserAsync(
        InviteUserDto inviteUserDto, IIdentityService identityService, HttpContext httpContext)
    {
        var inviterName = httpContext.User.Identity?.Name ?? "Someone";
        var result = await identityService.InviteUserAsync(
            httpContext.User.GetUserId(), inviterName, inviteUserDto);
        return result.ToHttpResult();
    }

    /// <summary>
    /// The page the mail links to, which asks rather than acts. Mail clients and security scanners
    /// fetch links they find, so a GET that did the linking was accepted by whatever opened the
    /// message instead of by the person reading it.
    /// </summary>
    private static async Task<IResult> ConfirmAccountLinkPageAsync(
        Guid userId,
        Guid inviterId,
        string token,
        IIdentityService identityService)
    {
        // Looked up rather than carried in the link, or the page could be made to name anybody as
        // the inviter by editing the address it was reached at.
        var invitedBy = await identityService.DisplayNameAsync(inviterId) ?? "Someone";
        return HtmlPage.ConfirmAccountLink(invitedBy, userId, inviterId, token);
    }

    /// <summary>
    /// Followed from the mail by the person who was invited, and by nobody else: holding the token
    /// is what says yes. Sending it links the accounts, which is why it is theirs to click.
    /// </summary>
    private static async Task<IResult> AcceptAccountLinkAsync(
        [FromForm] Guid userId,
        [FromForm] Guid inviterId,
        [FromForm] string token,
        IIdentityService identityService,
        IDeviceNotifier notifier)
    {
        var result = await identityService.AcceptAccountLinkAsync(userId, inviterId, token);
        if (!result.IsSuccess)
        {
            return HtmlPage.Message("That link did not work", result.ErrorMessage, StatusCodes.Status400BadRequest);
        }

        await notifier.LinksChangedAsync(userId, inviterId);
        return HtmlPage.Message(
            "Accounts linked",
            "Your phones and theirs can see each other now. Open the app to find them in the device list.");
    }

    private static async Task<IResult> ConfirmEmailPageAsync(
        Guid userId, string token, IIdentityService identityService)
    {
        var result = await identityService.ConfirmEmailAsync(new ConfirmEmailDto { UserId = userId, Token = token });
        return result.IsSuccess
            ? HtmlPage.Message("Email confirmed", "You can go back to the app and sign in.")
            : HtmlPage.Message("That link did not work", result.ErrorMessage, StatusCodes.Status400BadRequest);
    }

    private static async Task<IResult> ConfirmEmailChangePageAsync(
        Guid userId, string email, string token, IIdentityService identityService)
    {
        var result = await identityService.ConfirmEmailChangeAsync(
            new ConfirmEmailChangeDto { UserId = userId, Email = email, Token = token });

        return result.IsSuccess
            ? HtmlPage.Message("Email address changed", "Sign in with the new address from now on.")
            : HtmlPage.Message("That link did not work", result.ErrorMessage, StatusCodes.Status400BadRequest);
    }

    private static IResult ResetPasswordFormPage(
        string email, string token, IOptions<IdentityOptions> identityOptions) =>
        HtmlPage.ResetPasswordForm(email, token, identityOptions.Value.Password.RequiredLength);

    private static async Task<IResult> ResetPasswordFormAsync(
        [FromForm] string email,
        [FromForm] string token,
        [FromForm] string newPassword,
        IIdentityService identityService)
    {
        var result = await identityService.ResetPasswordAsync(new ResetPasswordDto
        {
            Email = email,
            Token = token,
            NewPassword = newPassword
        });

        return result.IsSuccess
            ? HtmlPage.Message("Password set", "You can go back to the app and sign in.")
            : HtmlPage.Message("That did not work", result.ErrorMessage, StatusCodes.Status400BadRequest);
    }

    private static async Task<IResult> LoginAsync(
        LoginDto loginDto,
        SignInManager<BabymonitorUser> signInManager,
        UserManager<BabymonitorUser> userManager,
        ILoggerFactory loggerFactory
    )
    {
        var logger = loggerFactory.CreateLogger(LogCategory);
        var email = loginDto.Email?.Trim();
        if (string.IsNullOrEmpty(email) || string.IsNullOrEmpty(loginDto.Password))
        {
            return Results.Problem(detail: BadCredentials, statusCode: StatusCodes.Status400BadRequest);
        }

        // Sign-in resolves by email only; a username is a display name and intentionally not an identifier.
        var user = await userManager.FindByEmailAsync(email);
        if (user == null)
        {
            logger.LogInformation("Failed login attempt for unknown email {Email}", email);
            return Results.Problem(detail: BadCredentials, statusCode: StatusCodes.Status400BadRequest);
        }

        // Counts failures against the account and locks it briefly once there have been too many.
        // Without it a four-character password is a few million unthrottled guesses away, and the
        // prize is a live microphone in a child's bedroom.
        var result = await signInManager.PasswordSignInAsync(
            user,
            loginDto.Password,
            isPersistent: true,
            lockoutOnFailure: true
        );

        if (result.RequiresTwoFactor)
        {
            // The password was right but no cookie is issued yet: SignInManager parked the user id in
            // the two-factor cookie, and login-2fa finishes the sign-in from there.
            logger.LogInformation(
                "User {UserId} {Username} passed the password step and needs a two-factor code", user.Id, user.UserName);
            return Results.Ok(new LoginResultDto { RequiresTwoFactor = true });
        }

        if (result.IsNotAllowed)
        {
            // Only reachable with the correct password, so naming the reason leaks nothing an attacker
            // doesn't already have — and without it a user with an unconfirmed address is simply stuck.
            logger.LogInformation(
                "User {UserId} {Username} tried to log in with an unconfirmed email address", user.Id, user.UserName);
            return Results.Problem(
                detail: "Please confirm your email address before signing in.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        if (result.IsLockedOut)
        {
            // Now reachable two ways: an administrator deactivated the account, or it has just
            // been locked by failed attempts. Both say the same thing to the caller, because
            // telling them apart would confirm the address exists.
            logger.LogWarning(
                "User {UserId} {Username} is locked out until {LockoutEnd}",
                user.Id, user.UserName, user.LockoutEnd);
            return Results.Problem(
                detail: "Too many attempts, or this account has been deactivated. "
                    + "Wait a few minutes, then try again.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        if (!result.Succeeded)
        {
            logger.LogInformation("Failed login attempt for user {UserId} {Username}", user.Id, user.UserName);
            return Results.Problem(detail: BadCredentials, statusCode: StatusCodes.Status400BadRequest);
        }

        logger.LogInformation("User {UserId} ({Username}) logged in", user.Id, user.UserName);
        return Results.Ok(new LoginResultDto
        {
            Username = user.UserName!,
            IsAdmin = await userManager.IsInRoleAsync(user, Roles.Admin)
        });
    }

    private static async Task<IResult> LoginTwoFactorAsync(
        TwoFactorLoginDto twoFactorLoginDto,
        SignInManager<BabymonitorUser> signInManager,
        ILoggerFactory loggerFactory
    )
    {
        var logger = loggerFactory.CreateLogger(LogCategory);
        var code = twoFactorLoginDto.Code?.Trim();
        if (string.IsNullOrEmpty(code))
        {
            return Results.Problem(
                detail: "Enter the code from your authenticator app.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        var user = await signInManager.GetTwoFactorAuthenticationUserAsync();
        if (user == null)
        {
            return Results.Problem(
                detail: "This sign-in has expired. Please enter your email and password again.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        // Six digits is an authenticator code; anything else is treated as a recovery code, which
        // Identity stores (and therefore matches) with its hyphen intact.
        var authenticatorCode = code
            .Replace(" ", string.Empty, StringComparison.Ordinal)
            .Replace("-", string.Empty, StringComparison.Ordinal);
        var isAuthenticatorCode = authenticatorCode.Length == 6 && authenticatorCode.All(char.IsAsciiDigit);

        var result = isAuthenticatorCode
            ? await signInManager.TwoFactorAuthenticatorSignInAsync(
                authenticatorCode, isPersistent: true, rememberClient: twoFactorLoginDto.RememberMachine)
            : await signInManager.TwoFactorRecoveryCodeSignInAsync(
                code.Replace(" ", string.Empty, StringComparison.Ordinal));

        if (!result.Succeeded)
        {
            logger.LogInformation(
                "Failed two-factor attempt for user {UserId} {Username} ({CodeKind})",
                user.Id, user.UserName, isAuthenticatorCode ? "authenticator code" : "recovery code");
            return Results.Problem(
                detail: isAuthenticatorCode
                    ? "That code isn't valid. Check your device's clock and try the next code."
                    : "That recovery code isn't valid or has already been used.",
                statusCode: StatusCodes.Status400BadRequest);
        }

        logger.LogInformation(
            "User {UserId} {Username} completed two-factor sign-in with a {CodeKind}",
            user.Id, user.UserName, isAuthenticatorCode ? "authenticator code" : "recovery code");
        return Results.Ok(new LoginResultDto
        {
            Username = user.UserName!,
            IsAdmin = await signInManager.UserManager.IsInRoleAsync(user, Roles.Admin)
        });
    }

    private static IResult GetAuthStatus(HttpContext httpContext)
    {
        return Results.Ok(new AuthStatusDto
        {
            Authenticated = httpContext.User.Identity?.IsAuthenticated ?? false,
            Username = httpContext.User.Identity?.Name ?? string.Empty,

            // Read off the cookie's role claims, so this costs no lookup. A grant or a revocation
            // rotates the user's security stamp, which is what gets the cookie re-issued.
            IsAdmin = httpContext.User.IsInRole(Roles.Admin)
        });
    }

    private static async Task<IResult> LogoutAsync(
        SignInManager<BabymonitorUser> signInManager, HttpContext httpContext, ILoggerFactory loggerFactory)
    {
        var logger = loggerFactory.CreateLogger(LogCategory);
        // Logout isn't behind RequireAuthorization, so the principal may carry no id — sign out regardless.
        httpContext.User.TryGetUserId(out var userId);
        var userName = httpContext.User.Identity?.Name;
        await signInManager.SignOutAsync();
        logger.LogInformation("User {UserId} {Username} logged out", userId, userName);
        return Results.Ok();
    }
}

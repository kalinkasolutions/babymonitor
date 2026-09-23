using System.Text;
using Babymonitor.BusinessLogic.Email;
using Babymonitor.BusinessLogic.Validation;
using Dal.Repositories.Account;
using Dtos.Account;
using Entities.Account;
using Microsoft.Extensions.Logging;
using Shared;

namespace Babymonitor.BusinessLogic.Services.Account;

public sealed class AccountService : IAccountService
{
    private const string Issuer = "Babymonitor";
    private const string IncorrectPassword = "That password is not correct.";
    private const int RecoveryCodeCount = 10;

    private readonly ILogger<AccountService> m_logger;
    private readonly IAccountRepository m_accountRepository;
    private readonly IEmailService m_emailService;

    public AccountService(
        ILogger<AccountService> logger,
        IAccountRepository accountRepository,
        IEmailService emailService
    )
    {
        m_logger = logger;
        m_accountRepository = accountRepository;
        m_emailService = emailService;
    }

    public async Task<OperationResult<AccountDto>> GetAsync(Guid userId)
    {
        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<AccountDto>.NotFound("Account not found.");
        }

        return OperationResult<AccountDto>.Success(await ToDtoAsync(user));
    }

    public async Task<OperationResult<AccountDto>> UpdateUsernameAsync(Guid userId, UpdateUsernameDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<AccountDto>();
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<AccountDto>.NotFound("Account not found.");
        }

        // A blank name is already off the table: [Required] rejects whitespace-only strings.
        var username = dto.Username.Trim();
        if (string.Equals(username, user.UserName, StringComparison.Ordinal))
        {
            return OperationResult<AccountDto>.Success(await ToDtoAsync(user));
        }

        var result = await m_accountRepository.SetUsernameAsync(user, username);
        if (result.HasError)
        {
            m_logger.LogInformation(
                "User {UserId} could not change display name to {Username}: {Error}",
                userId, username, result.ErrorMessage);
            return result.MapError<AccountDto>();
        }

        m_logger.LogInformation(
            "User {UserId} {PreviousUsername} changed display name to {Username}",
            userId, user.UserName, username);
        return OperationResult<AccountDto>.Success(await ToDtoAsync(user));
    }

    public async Task<OperationResult<Empty>> RequestEmailChangeAsync(Guid userId, ChangeEmailDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        if (!await m_accountRepository.CheckPasswordAsync(user, dto.CurrentPassword))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} gave the wrong password when changing email", userId, user.UserName);
            return OperationResult<Empty>.BadRequest(IncorrectPassword);
        }

        var email = dto.Email.Trim();
        if (string.Equals(email, user.Email, StringComparison.OrdinalIgnoreCase))
        {
            return OperationResult<Empty>.BadRequest("That is already your email address.");
        }

        // The address is the sign-in identifier and must stay unique, so reject a taken one here
        // with a message the user can act on rather than failing when the token is redeemed.
        if (await m_accountRepository.FindByEmailAsync(email) is not null)
        {
            return OperationResult<Empty>.BadRequest("That email address is already in use.");
        }

        var token = await m_accountRepository.GenerateChangeEmailTokenAsync(user, email);
        var mailResult = await m_emailService.SendChangeEmailMailAsync(email, user.Id, token);
        if (mailResult.HasError)
        {
            // Nothing changed yet, so unlike registration this is worth reporting: without the mail
            // the user has no way to complete the change.
            m_logger.LogWarning(
                "Email change confirmation could not be sent to {Email} for user {UserId}", email, userId);
            return mailResult;
        }

        m_logger.LogInformation(
            "User {UserId} {Username} requested an email change to {Email}", userId, user.UserName, email);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> SignOutEverywhereAsync(Guid userId)
    {
        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        await m_accountRepository.UpdateSecurityStampAsync(user);
        m_logger.LogInformation("User {UserId} {Username} signed out all sessions", userId, user.UserName);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> DeleteAsync(Guid userId, DeleteAccountDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        if (!await m_accountRepository.CheckPasswordAsync(user, dto.CurrentPassword))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} gave the wrong password when deleting their account",
                userId, user.UserName);
            return OperationResult<Empty>.BadRequest(IncorrectPassword);
        }

        var result = await m_accountRepository.DeleteAsync(user);
        if (result.HasError)
        {
            return result;
        }

        m_logger.LogInformation("User {UserId} {Username} deleted their account", userId, user.UserName);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> ChangePasswordAsync(Guid userId, ChangePasswordDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        // Checked up front so a wrong current password reads as such, rather than as Identity's
        // generic "Incorrect password." mixed in with new-password rule violations.
        if (!await m_accountRepository.CheckPasswordAsync(user, dto.CurrentPassword))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} gave the wrong current password when changing password",
                userId, user.UserName);
            return OperationResult<Empty>.BadRequest(IncorrectPassword);
        }

        var result = await m_accountRepository.ChangePasswordAsync(user, dto.CurrentPassword, dto.NewPassword);
        if (result.HasError)
        {
            m_logger.LogInformation("Password change failed for user {UserId} {Username}", userId, user.UserName);
            return result;
        }

        m_logger.LogInformation("User {UserId} {Username} changed their password", userId, user.UserName);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<TwoFactorSetupDto>> GetTwoFactorSetupAsync(Guid userId)
    {
        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<TwoFactorSetupDto>.NotFound("Account not found.");
        }

        if (user.TwoFactorEnabled)
        {
            return OperationResult<TwoFactorSetupDto>.BadRequest(
                "Two-factor authentication is already on. Turn it off first to set up a new authenticator.");
        }

        // Reuse a secret from an abandoned setup so reopening the screen doesn't invalidate a code the
        // user already scanned; it only becomes the account's second factor once a code verifies.
        var key = await m_accountRepository.GetAuthenticatorKeyAsync(user);
        if (string.IsNullOrEmpty(key))
        {
            key = await m_accountRepository.ResetAuthenticatorKeyAsync(user);
        }

        return OperationResult<TwoFactorSetupDto>.Success(new TwoFactorSetupDto
        {
            SharedKey = FormatKey(key),
            AuthenticatorUri = BuildAuthenticatorUri(user.Email!, key)
        });
    }

    public async Task<OperationResult<RecoveryCodesDto>> EnableTwoFactorAsync(Guid userId, EnableTwoFactorDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<RecoveryCodesDto>();
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<RecoveryCodesDto>.NotFound("Account not found.");
        }

        if (user.TwoFactorEnabled)
        {
            return OperationResult<RecoveryCodesDto>.BadRequest("Two-factor authentication is already on.");
        }

        var key = await m_accountRepository.GetAuthenticatorKeyAsync(user);
        if (string.IsNullOrEmpty(key))
        {
            return OperationResult<RecoveryCodesDto>.BadRequest(
                "Start the setup again — there is no authenticator secret waiting to be confirmed.");
        }

        if (!await m_accountRepository.VerifyAuthenticatorCodeAsync(user, StripSeparators(dto.Code)))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} submitted an invalid authenticator code while enabling 2FA",
                userId, user.UserName);
            return OperationResult<RecoveryCodesDto>.BadRequest(
                "That code isn't valid. Check your device's clock and try the next code.");
        }

        var enableResult = await m_accountRepository.SetTwoFactorEnabledAsync(user, true);
        if (enableResult.HasError)
        {
            return enableResult.MapError<RecoveryCodesDto>();
        }

        var codes = await m_accountRepository.GenerateRecoveryCodesAsync(user, RecoveryCodeCount);
        m_logger.LogInformation(
            "User {UserId} {Username} enabled two-factor authentication", userId, user.UserName);

        return OperationResult<RecoveryCodesDto>.Success(new RecoveryCodesDto { Codes = codes });
    }

    public async Task<OperationResult<Empty>> DisableTwoFactorAsync(Guid userId, DisableTwoFactorDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        if (!await m_accountRepository.CheckPasswordAsync(user, dto.CurrentPassword))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} gave the wrong password when disabling 2FA", userId, user.UserName);
            return OperationResult<Empty>.BadRequest(IncorrectPassword);
        }

        if (!user.TwoFactorEnabled)
        {
            return OperationResult<Empty>.BadRequest("Two-factor authentication is already off.");
        }

        var result = await m_accountRepository.SetTwoFactorEnabledAsync(user, false);
        if (result.HasError)
        {
            return result;
        }

        // Drop the secret too: a later re-enable should pair a fresh one rather than silently accept
        // codes from an authenticator entry the user may have removed months ago.
        await m_accountRepository.ResetAuthenticatorKeyAsync(user);

        m_logger.LogInformation(
            "User {UserId} {Username} disabled two-factor authentication", userId, user.UserName);
        return OperationResult<Empty>.Success();
    }

    /// <summary>
    /// Takes the password, like turning two-factor off does. Fresh codes retire the old ones and
    /// are ten standing bypasses of the second factor, so handing them out on the strength of a
    /// cookie alone made the cookie as good as the password it was meant to be a second factor to.
    /// </summary>
    public async Task<OperationResult<RecoveryCodesDto>> RegenerateRecoveryCodesAsync(
        Guid userId, DisableTwoFactorDto dto)
    {
        var validation = DtoValidator.Validate(dto);
        if (validation.HasError)
        {
            return validation.MapError<RecoveryCodesDto>();
        }

        var user = await m_accountRepository.FindByIdAsync(userId);
        if (user is null)
        {
            return OperationResult<RecoveryCodesDto>.NotFound("Account not found.");
        }

        if (!await m_accountRepository.CheckPasswordAsync(user, dto.CurrentPassword))
        {
            m_logger.LogInformation(
                "User {UserId} {Username} gave the wrong password when regenerating recovery codes",
                userId, user.UserName);
            return OperationResult<RecoveryCodesDto>.BadRequest(IncorrectPassword);
        }

        if (!user.TwoFactorEnabled)
        {
            return OperationResult<RecoveryCodesDto>.BadRequest(
                "Turn on two-factor authentication first — there is nothing to recover yet.");
        }

        var codes = await m_accountRepository.GenerateRecoveryCodesAsync(user, RecoveryCodeCount);
        m_logger.LogInformation(
            "User {UserId} {Username} regenerated their recovery codes", userId, user.UserName);

        return OperationResult<RecoveryCodesDto>.Success(new RecoveryCodesDto { Codes = codes });
    }

    private async Task<AccountDto> ToDtoAsync(BabymonitorUser user) => new()
    {
        UserId = user.Id,
        Username = user.UserName!,
        Email = user.Email!,
        EmailConfirmed = user.EmailConfirmed,
        TwoFactorEnabled = user.TwoFactorEnabled,
        RecoveryCodesLeft = user.TwoFactorEnabled
            ? await m_accountRepository.CountRecoveryCodesAsync(user)
            : 0,
        MemberSince = user.CreatedAt
    };

    private static string BuildAuthenticatorUri(string email, string key)
    {
        return $"otpauth://totp/{Uri.EscapeDataString($"{Issuer}:{email}")}" +
               $"?secret={key}" +
               $"&issuer={Uri.EscapeDataString(Issuer)}" +
               "&digits=6";
    }

    /// <summary>Groups the secret in fours, which is how every authenticator app presents a typed key.</summary>
    private static string FormatKey(string key)
    {
        var formatted = new StringBuilder();
        for (var position = 0; position < key.Length; position += 4)
        {
            if (position > 0)
            {
                formatted.Append(' ');
            }

            formatted.Append(key.AsSpan(position, Math.Min(4, key.Length - position)));
        }

        return formatted.ToString();
    }

    /// <summary>Authenticator apps show codes as "123 456"; accept whatever spacing the user typed.</summary>
    private static string StripSeparators(string code)
    {
        return code.Replace(" ", string.Empty, StringComparison.Ordinal)
            .Replace("-", string.Empty, StringComparison.Ordinal);
    }
}

using Babyphone.BusinessLogic.Email;
using Babyphone.BusinessLogic.Validation;
using Dal.Repositories.Devices;
using Dal.Repositories.Identity;
using Dtos.Auth;
using Entities.Account;
using Microsoft.Extensions.Logging;
using Shared;

namespace Babyphone.BusinessLogic.Services.Identity;

public sealed class IdentityService : IIdentityService
{
    private readonly ILogger<IdentityService> m_logger;
    private readonly IIdentityRepository m_identityRepository;
    private readonly IEmailService m_emailService;
    private readonly IPairingRepository m_pairingRepository;

    public IdentityService(
        ILogger<IdentityService> logger,
        IIdentityRepository identityRepository,
        IEmailService emailService,
        IPairingRepository pairingRepository
    )
    {
        m_logger = logger;
        m_identityRepository = identityRepository;
        m_emailService = emailService;
        m_pairingRepository = pairingRepository;
    }

    public async Task<OperationResult<Empty>> RegisterUserAsync(RegisterUserDto registerUserDto)
    {
        var validation = DtoValidator.Validate(registerUserDto);
        if (validation.HasError)
        {
            return validation;
        }

        var username = registerUserDto.Username.Trim();
        var email = registerUserDto.Email.Trim();
        m_logger.LogInformation("Registration requested for username {Username} <{Email}>", username, email);

        var registerResult = await m_identityRepository.RegisterUserAsync(
            new BabyphoneUser
            {
                UserName = username,
                Email = email
            },
            registerUserDto.Password);

        if (registerResult.HasError)
        {
            m_logger.LogInformation(
                "Registration failed for username {Username}: {Error}", username, registerResult.ErrorMessage);
            return registerResult.MapError<Empty>();
        }

        var user = registerResult.Value;
        await GrantUserRoleAsync(user);

        m_logger.LogInformation("Registered new user {UserId} ({Username})", user.Id, user.UserName);
        await SendConfirmationAsync(user);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> ConfirmEmailAsync(ConfirmEmailDto confirmEmailDto)
    {
        var validation = DtoValidator.Validate(confirmEmailDto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_identityRepository.FindByIdAsync(confirmEmailDto.UserId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        if (user.EmailConfirmed)
        {
            // Clicking the link twice is normal — mail clients prefetch — and is not a failure.
            return OperationResult<Empty>.Success();
        }

        var result = await m_identityRepository.ConfirmEmailAsync(user, confirmEmailDto.Token);
        if (result.HasError)
        {
            m_logger.LogInformation("Email confirmation failed for user {UserId}", user.Id);
            return result;
        }

        m_logger.LogInformation("User {UserId} ({Username}) confirmed their email", user.Id, user.UserName);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> ResendConfirmationAsync(ResendConfirmationDto resendConfirmationDto)
    {
        var validation = DtoValidator.Validate(resendConfirmationDto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_identityRepository.FindByEmailAsync(resendConfirmationDto.Email.Trim());

        // Always the same answer, so the endpoint cannot be used to find out which addresses exist.
        if (user is not null && !user.EmailConfirmed)
        {
            await SendConfirmationAsync(user);
        }

        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> ConfirmEmailChangeAsync(ConfirmEmailChangeDto confirmEmailChangeDto)
    {
        var validation = DtoValidator.Validate(confirmEmailChangeDto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_identityRepository.FindByIdAsync(confirmEmailChangeDto.UserId);
        if (user is null)
        {
            return OperationResult<Empty>.NotFound("Account not found.");
        }

        var email = confirmEmailChangeDto.Email.Trim();
        var result = await m_identityRepository.ChangeEmailAsync(user, email, confirmEmailChangeDto.Token);
        if (result.HasError)
        {
            m_logger.LogInformation("Email change failed for user {UserId}", user.Id);
            return result;
        }

        m_logger.LogInformation("User {UserId} moved their account to {Email}", user.Id, email);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> SendPasswordResetAsync(ForgotPasswordDto forgotPasswordDto)
    {
        var validation = DtoValidator.Validate(forgotPasswordDto);
        if (validation.HasError)
        {
            return validation;
        }

        var email = forgotPasswordDto.Email.Trim();
        var user = await m_identityRepository.FindByEmailAsync(email);

        // Same answer for a known and an unknown address, for the same reason as above.
        if (user is null)
        {
            m_logger.LogInformation("Password reset requested for unknown email {Email}", email);
            return OperationResult<Empty>.Success();
        }

        var token = await m_identityRepository.GeneratePasswordResetTokenAsync(user);
        var mailResult = await m_emailService.SendResetPasswordMailAsync(user.Email!, token);
        if (mailResult.HasError)
        {
            m_logger.LogWarning("Password reset email could not be sent to {Email}", user.Email);
        }

        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> ResetPasswordAsync(ResetPasswordDto resetPasswordDto)
    {
        var validation = DtoValidator.Validate(resetPasswordDto);
        if (validation.HasError)
        {
            return validation;
        }

        var user = await m_identityRepository.FindByEmailAsync(resetPasswordDto.Email.Trim());
        if (user is null)
        {
            // A bad address and a bad token are the same thing to the caller, and saying which
            // would turn this into an address oracle.
            return OperationResult<Empty>.BadRequest("That reset link is not valid any more.");
        }

        var result = await m_identityRepository.ResetPasswordAsync(
            user, resetPasswordDto.Token, resetPasswordDto.NewPassword);
        if (result.HasError)
        {
            m_logger.LogInformation("Password reset failed for user {UserId}", user.Id);
            return result;
        }

        // Setting a password through a link mailed to the address also proves the address, which is
        // the whole mechanism behind an invitation.
        if (!user.EmailConfirmed)
        {
            var confirmToken = await m_identityRepository.GenerateEmailConfirmationTokenAsync(user);
            await m_identityRepository.ConfirmEmailAsync(user, confirmToken);
        }

        m_logger.LogInformation("User {UserId} ({Username}) reset their password", user.Id, user.UserName);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> InviteUserAsync(
        Guid inviterId, string inviterName, InviteUserDto inviteUserDto)
    {
        var validation = DtoValidator.Validate(inviteUserDto);
        if (validation.HasError)
        {
            return validation;
        }

        var email = inviteUserDto.Email.Trim();
        var username = inviteUserDto.Username.Trim();

        var existing = await m_identityRepository.FindByEmailAsync(email);
        if (existing is not null)
        {
            return await InviteExistingAccountAsync(inviterId, inviterName, existing);
        }

        var createResult = await m_identityRepository.CreateInvitedUserAsync(
            new BabyphoneUser
            {
                UserName = username,
                Email = email
            });

        if (createResult.HasError)
        {
            return createResult.MapError<Empty>();
        }

        var user = createResult.Value;
        await GrantUserRoleAsync(user);

        // Linked straight away rather than on acceptance: the invitation went to an address the
        // inviter chose, and the account it created has no other purpose.
        var linkResult = await m_pairingRepository.LinkAccountsAsync(inviterId, user.Id);
        if (linkResult.HasError)
        {
            m_logger.LogError(
                "Could not link invited user {UserId} to inviter {InviterId}: {Error}",
                user.Id, inviterId, linkResult.ErrorMessage);
        }

        // A reset token, not a confirmation token: the account has no password yet, and the link
        // has to set one. Redeeming it confirms the address at the same time (see ResetPasswordAsync).
        var token = await m_identityRepository.GeneratePasswordResetTokenAsync(user);
        var mailResult = await m_emailService.SendInviteMailAsync(user.Email!, inviterName, token);
        if (mailResult.HasError)
        {
            m_logger.LogWarning("Invitation email could not be sent to {Email}", user.Email);
            return mailResult;
        }

        m_logger.LogInformation(
            "User {InviterId} ({InviterName}) invited {Email} as user {UserId}",
            inviterId, inviterName, email, user.Id);
        return OperationResult<Empty>.Success();
    }

    public async Task<OperationResult<Empty>> AcceptAccountLinkAsync(Guid userId, Guid inviterId, string token)
    {
        // One message for every way this can fail: which part of a stale link was wrong is no use
        // to the person holding it, and would say whether an account exists.
        const string badLink = "That invitation is not valid any more. Ask for a new one.";

        var user = await m_identityRepository.FindByIdAsync(userId);
        if (user is null || !await m_identityRepository.VerifyAccountLinkTokenAsync(user, inviterId, token))
        {
            m_logger.LogInformation(
                "Rejected an account-link token for user {UserId} from inviter {InviterId}", userId, inviterId);
            return OperationResult<Empty>.BadRequest(badLink);
        }

        var inviter = await m_identityRepository.FindByIdAsync(inviterId);
        if (inviter is null)
        {
            return OperationResult<Empty>.BadRequest(badLink);
        }

        var linkResult = await m_pairingRepository.LinkAccountsAsync(inviterId, userId);
        if (linkResult.HasError)
        {
            return linkResult;
        }

        m_logger.LogInformation("User {UserId} accepted a link to {InviterId}", userId, inviterId);
        return OperationResult<Empty>.Success();
    }

    /// <summary>
    /// An address that already has an account is somebody else's account. It gets an invitation to
    /// accept, not a link made on its behalf — otherwise anyone who knows an address could put
    /// themselves in that person's device list. The reply is the same either way, so this is not a
    /// way to find out which addresses are registered.
    /// </summary>
    private async Task<OperationResult<Empty>> InviteExistingAccountAsync(
        Guid inviterId, string inviterName, BabyphoneUser invitee)
    {
        if (invitee.Id == inviterId)
        {
            return OperationResult<Empty>.BadRequest("That is your own address.");
        }

        var token = await m_identityRepository.GenerateAccountLinkTokenAsync(invitee, inviterId);
        var mailResult = await m_emailService.SendAccountLinkMailAsync(
            invitee.Email!, inviterName, invitee.Id, inviterId, token);

        if (mailResult.HasError)
        {
            m_logger.LogWarning("Invitation email could not be sent to {Email}", invitee.Email);
            return mailResult;
        }

        m_logger.LogInformation(
            "User {InviterId} ({InviterName}) asked existing user {UserId} to link accounts",
            inviterId, inviterName, invitee.Id);
        return OperationResult<Empty>.Success();
    }

    private async Task GrantUserRoleAsync(BabyphoneUser user)
    {
        var roleResult = await m_identityRepository.AddToRolesAsync(user, Roles.User);
        if (roleResult.HasError)
        {
            m_logger.LogError(
                "Could not assign roles to new user {UserId}: {Error}", user.Id, roleResult.ErrorMessage);
        }
    }

    /// <summary>
    /// The account exists regardless of whether the mail goes out; a delivery failure is an ops
    /// concern, not a registration failure, so it is logged and the caller still sees success.
    /// </summary>
    private async Task SendConfirmationAsync(BabyphoneUser user)
    {
        var token = await m_identityRepository.GenerateEmailConfirmationTokenAsync(user);
        var mailResult = await m_emailService.SendConfirmationMailAsync(user.Email!, user.Id, token);
        if (mailResult.HasError)
        {
            m_logger.LogWarning("Confirmation email could not be sent to {Email}", user.Email);
        }
    }
}

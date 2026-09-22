using Dtos.Auth;
using Shared;

namespace Babyphone.BusinessLogic.Services.Identity;

public interface IIdentityService
{
    Task<OperationResult<Empty>> RegisterUserAsync(RegisterUserDto registerUserDto);
    Task<OperationResult<Empty>> ConfirmEmailAsync(ConfirmEmailDto confirmEmailDto);
    Task<OperationResult<Empty>> ResendConfirmationAsync(ResendConfirmationDto resendConfirmationDto);
    Task<OperationResult<Empty>> ConfirmEmailChangeAsync(ConfirmEmailChangeDto confirmEmailChangeDto);
    Task<OperationResult<Empty>> SendPasswordResetAsync(ForgotPasswordDto forgotPasswordDto);
    Task<OperationResult<Empty>> ResetPasswordAsync(ResetPasswordDto resetPasswordDto);

    /// <summary>
    /// Invites somebody who is not in the room. A new address gets a passwordless account, linked
    /// straight away, and a mail to set a password; an address that already has an account gets a
    /// mail asking whether it wants to be linked, because that account is not the inviter's to
    /// give away. Either way the keys stay unverified until somebody compares them, which is the
    /// cost of not being in the same room.
    /// </summary>
    Task<OperationResult<Empty>> InviteUserAsync(Guid inviterId, string inviterName, InviteUserDto inviteUserDto);

    /// <summary>Redeems an invitation link and links the two accounts.</summary>
    Task<OperationResult<Empty>> AcceptAccountLinkAsync(Guid userId, Guid inviterId, string token);
}

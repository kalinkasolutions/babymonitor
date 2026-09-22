using Entities.Account;
using Microsoft.AspNetCore.Identity;

namespace Babyphone.BusinessLogic.Validation;

/// <summary>
/// Identity's own user validator with the duplicate-name rule dropped, because
/// <see cref="BabyphoneUser.UserName"/> is a display name and an account is identified by its email
/// address. Filtering the base result rather than reimplementing it keeps every other rule —
/// allowed characters, email format, email uniqueness — exactly as Identity defines it.
/// </summary>
public sealed class DisplayNameUserValidator : UserValidator<BabyphoneUser>
{
    public DisplayNameUserValidator(IdentityErrorDescriber? errors = null) : base(errors)
    {
    }

    public override async Task<IdentityResult> ValidateAsync(UserManager<BabyphoneUser> manager, BabyphoneUser user)
    {
        var result = await base.ValidateAsync(manager, user);
        if (result.Succeeded)
        {
            return result;
        }

        var duplicateName = Describer.DuplicateUserName(string.Empty).Code;
        var errors = result.Errors.Where(e => e.Code != duplicateName).ToArray();

        return errors.Length == 0 ? IdentityResult.Success : IdentityResult.Failed(errors);
    }
}

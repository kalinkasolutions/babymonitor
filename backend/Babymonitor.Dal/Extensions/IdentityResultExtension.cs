using Microsoft.AspNetCore.Identity;

namespace Dal.Extensions;

public static class IdentityResultExtension
{
    public static string ToErrorString(this IdentityResult identityResult)
    {
        return string.Join(Environment.NewLine, identityResult.Errors.Select(e => e.Description));
    }
}

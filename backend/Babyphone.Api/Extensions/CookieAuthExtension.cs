using Microsoft.AspNetCore.Identity;

namespace Babyphone.Extensions;

public static class CookieAuthExtension
{
    /// <summary>
    /// Configures the Identity application cookie and how long a cookie may outlive a security-stamp
    /// change. Call after <c>AddIdentity</c>, which is what registers the schemes this configures.
    /// </summary>
    public static IServiceCollection ConfigureCookieAuth(this IServiceCollection services)
    {
        // AddIdentity has already registered the cookie schemes and pinned Identity.Application as the
        // default authenticate/challenge scheme, so the app cookie has to be configured through
        // ConfigureApplicationCookie. Registering a separate "Cookies" scheme instead would leave its
        // expiry and events dead config while the Identity cookie kept its own defaults.
        services.ConfigureApplicationCookie(options =>
        {
            // A phone in a nursery should not be signed out because nobody opened the app for a month.
            options.ExpireTimeSpan = TimeSpan.FromDays(365);
            options.SlidingExpiration = true;

            // Every caller is a native client using an HTTP library, so answer a missing or stale
            // cookie with a status code it can act on rather than a 302 to a login page.
            options.Events.OnRedirectToLogin = context =>
            {
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                return Task.CompletedTask;
            };
            options.Events.OnRedirectToAccessDenied = context =>
            {
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                return Task.CompletedTask;
            };
        });

        // How long a cookie may outlive a security-stamp change (a password change, "sign out
        // everywhere"). Until the next validation pass an already-issued cookie keeps working, so the
        // default 30 minutes would make revoking a device look like it did nothing.
        services.Configure<SecurityStampValidatorOptions>(options =>
            options.ValidationInterval = TimeSpan.FromMinutes(1));

        return services;
    }
}

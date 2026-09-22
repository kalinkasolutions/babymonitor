using System.Security.Claims;
using Serilog;
using Serilog.Context;

namespace Babyphone.Extensions;

public static class UserLogContextExtension
{
    /// <summary>
    /// Tags every log line of an authenticated request with who made it. The display name rides in
    /// the Identity cookie as the <see cref="ClaimTypes.Name"/> claim, so this costs no user lookup —
    /// which is why the caller is named here rather than threaded through every service signature.
    /// It lands as a log property, not in the rendered text: an enricher cannot fill a placeholder
    /// in a message template.
    /// </summary>
    public static IApplicationBuilder UseUserLogContext(this IApplicationBuilder app) =>
        app.Use(async (context, next) =>
        {
            if (!context.User.TryGetUserId(out var userId))
            {
                await next(context);
                return;
            }

            var userName = context.User.FindFirstValue(ClaimTypes.Name);

            // Also on the request-summary line, which Serilog writes from a middleware sitting
            // outside this one — by then these scopes are long disposed.
            var diagnostics = context.RequestServices.GetRequiredService<IDiagnosticContext>();
            diagnostics.Set("UserId", userId);

            // 'Username', not 'UserName' — Serilog's property bag and SQLite's json_extract paths
            // are both case-sensitive, and that is the spelling the log readers ask for.
            diagnostics.Set("Username", userName);

            using (LogContext.PushProperty("UserId", userId))
            using (LogContext.PushProperty("Username", userName))
            {
                await next(context);
            }
        });
}

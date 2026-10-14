using Babymonitor.Auth;
using Babymonitor.Turn;

namespace Babymonitor.Endpoints;

public static class StatusEndpoint
{
    private sealed record Status(bool Ok);

    private sealed record KeyStatus(bool Ok, bool HomeRelay);

    public static void MapStatusEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapGet("api/status", () => new Status(Ok: true));

        // Lets the app say "wrong server key" rather than a vaguer failure.
        builder.MapGet("api/status/key", CheckKey);
    }

    private static IResult CheckKey(HttpContext httpContext, ServerKey serverKey, TurnService turnService)
    {
        // Apps up to 0.0.5 always check a key here, and a server without one must not fail them.
        if (!serverKey.IsSet)
        {
            return Results.Ok(new KeyStatus(Ok: true, HomeRelay: false));
        }

        return serverKey.Matches(httpContext.Request.Headers[ServerKey.HeaderName])
            ? Results.Ok(new KeyStatus(Ok: true, HomeRelay: turnService.HasHomeRelay))
            : Results.Unauthorized();
    }
}

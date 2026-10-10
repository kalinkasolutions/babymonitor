using Babymonitor.Auth;

namespace Babymonitor.Endpoints;

public static class StatusEndpoint
{
    private sealed record Status(bool Ok);

    public static void MapStatusEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapGet("api/status", () => new Status(Ok: true));

        // Lets the app say "wrong server key" rather than a vaguer failure.
        builder.MapGet("api/status/key", CheckKey);
    }

    private static IResult CheckKey(HttpContext httpContext, ServerKey serverKey) =>
        serverKey.Matches(httpContext.Request.Headers[ServerKey.HeaderName])
            ? Results.Ok(new Status(Ok: true))
            : Results.Unauthorized();
}

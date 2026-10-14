using Babymonitor.Auth;
using Babymonitor.Extensions;
using Babymonitor.Turn;

namespace Babymonitor.Endpoints;

public static class TurnEndpoint
{
    public static void MapTurnEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapGet("api/turn/credentials", Issue).RequireAuthorization();
    }

    private static IceServers Issue(TurnService turnService, ServerKey serverKey, HttpContext httpContext) =>
        turnService.Issue(
            httpContext.User.GetDeviceId(),
            withHomeRelay: serverKey.Matches(httpContext.Request.Headers[ServerKey.HeaderName]));
}

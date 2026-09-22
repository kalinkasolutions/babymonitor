using Babyphone.BusinessLogic.Services.Turn;
using Babyphone.Extensions;

namespace Babyphone.Endpoints;

public static class TurnEndpoint
{
    public static void MapTurnEndpoint(this IEndpointRouteBuilder builder)
    {
        builder.MapGet("api/turn/credentials", IssueAsync).RequireAuthorization();
    }

    private static IResult IssueAsync(ITurnService turnService, HttpContext httpContext)
    {
        return turnService.IssueAsync(httpContext.User.GetUserId()).ToHttpResult();
    }
}

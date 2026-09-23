using Babymonitor.BusinessLogic.Services.Devices;
using Babymonitor.Extensions;
using Babymonitor.Realtime;
using Dtos.Devices;

namespace Babymonitor.Endpoints;

public static class PairingEndpoint
{
    /// <summary>
    /// Throttles code guessing. Eight characters out of a 32-letter alphabet are not guessable in
    /// the five minutes a code lives, but nothing should be able to sit there trying either.
    /// </summary>
    public const string ClaimPolicy = "pairing-claim";

    public static void MapPairingEndpoint(this IEndpointRouteBuilder builder)
    {
        var group = builder.MapGroup("api/pairing").RequireAuthorization();
        group.MapPost("code", CreateCodeAsync);
        group.MapPost("claim", ClaimAsync).RequireRateLimiting(ClaimPolicy);
        group.MapDelete("link/{otherUserId:guid}", UnlinkAsync);
    }

    private static async Task<IResult> CreateCodeAsync(IPairingService pairingService, HttpContext httpContext)
    {
        var result = await pairingService.CreateCodeAsync(httpContext.User.GetUserId(), httpContext.DeviceId());
        return result.ToHttpResult();
    }

    private static async Task<IResult> ClaimAsync(
        ClaimPairingDto dto,
        IPairingService pairingService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await pairingService.ClaimAsync(userId, httpContext.DeviceId(), dto);
        if (!result.IsSuccess)
        {
            return result.ToHttpResult();
        }

        var claim = result.Value;
        await notifier.LinksChangedAsync(userId, claim.ShowingDevice.OwnerId);

        // The phone that showed the code needs to know who scanned it, and with what key, before
        // it can trust them. Everything it needs travels in this one announcement.
        await notifier.PairingCompletedAsync(userId, claim.ShowingDevice.OwnerId, new PairingCompletedDto
        {
            ShowingDeviceId = claim.ShowingDevice.Id,
            ClaimingDevice = claim.ClaimingDevice,
            Proof = claim.Proof
        });

        return Results.Ok(claim.ShowingDevice);
    }

    private static async Task<IResult> UnlinkAsync(
        Guid otherUserId,
        IPairingService pairingService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await pairingService.UnlinkAsync(userId, otherUserId);
        if (result.IsSuccess)
        {
            await notifier.LinksChangedAsync(userId, otherUserId);
        }

        return result.ToHttpResult();
    }
}

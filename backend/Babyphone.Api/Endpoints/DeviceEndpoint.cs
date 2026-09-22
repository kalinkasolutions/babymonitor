using Babyphone.BusinessLogic.Services.Devices;
using Babyphone.Extensions;
using Babyphone.Realtime;
using Dtos.Devices;

namespace Babyphone.Endpoints;

public static class DeviceEndpoint
{
    public static void MapDeviceEndpoint(this IEndpointRouteBuilder builder)
    {
        var group = builder.MapGroup("api/devices").RequireAuthorization();
        group.MapGet("", ListAsync);
        group.MapPost("", RegisterAsync);
        group.MapPut("{deviceId:guid}/name", RenameAsync);
        group.MapPost("{deviceId:guid}/heartbeat", HeartbeatAsync);
        group.MapDelete("{deviceId:guid}", RevokeAsync);
    }

    private static async Task<IResult> ListAsync(
        IDeviceService deviceService,
        DeviceConnections connections,
        HttpContext httpContext)
    {
        var result = await deviceService.ListAsync(httpContext.User.GetUserId(), httpContext.DeviceId());
        if (!result.IsSuccess)
        {
            return result.ToHttpResult();
        }

        // Filled in here rather than in the service: who is connected is a fact about this
        // process's sockets, not about the database.
        var withPresence = result.Value
            .ConvertAll(device => device with { IsOnline = connections.IsConnected(device.Id) });

        return Results.Ok(withPresence);
    }

    private static async Task<IResult> RegisterAsync(
        RegisterDeviceDto dto,
        IDeviceService deviceService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await deviceService.RegisterAsync(userId, dto);
        if (result.IsSuccess)
        {
            await notifier.DeviceChangedAsync(userId, result.Value);
        }

        return result.ToHttpResult();
    }

    private static async Task<IResult> RenameAsync(
        Guid deviceId,
        RenameDeviceDto dto,
        IDeviceService deviceService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await deviceService.RenameAsync(userId, deviceId, httpContext.DeviceId(), dto);
        if (result.IsSuccess)
        {
            await notifier.DeviceChangedAsync(userId, result.Value);
        }

        return result.ToHttpResult();
    }

    /// <summary>
    /// A heartbeat is a phone reporting in, not a phone asking a question, so it stays an upload.
    /// What it produces — a battery reading the other phone cares about — goes out over the hub,
    /// so nobody has to ask for it.
    /// </summary>
    private static async Task<IResult> HeartbeatAsync(
        Guid deviceId,
        HeartbeatDto dto,
        IDeviceService deviceService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await deviceService.HeartbeatAsync(userId, deviceId, dto);
        if (result.IsSuccess)
        {
            var refreshed = await deviceService.FindAsync(userId, deviceId, httpContext.DeviceId());
            if (refreshed.IsSuccess)
            {
                await notifier.DeviceChangedAsync(userId, refreshed.Value);
            }
        }

        return result.ToHttpResult();
    }

    private static async Task<IResult> RevokeAsync(
        Guid deviceId,
        IDeviceService deviceService,
        IDeviceNotifier notifier,
        HttpContext httpContext)
    {
        var userId = httpContext.User.GetUserId();
        var result = await deviceService.RevokeAsync(userId, deviceId);
        if (result.IsSuccess)
        {
            await notifier.DeviceRemovedAsync(userId, deviceId);
        }

        return result.ToHttpResult();
    }
}

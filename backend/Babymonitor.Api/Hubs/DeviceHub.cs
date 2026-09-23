using System.Text.Json;
using Babymonitor.Extensions;
using Babymonitor.Realtime;
using Dal.Repositories.Devices;
using Dtos.Devices;
using Entities.Devices;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace Babymonitor.Hubs;

/// <summary>
/// The feed a phone listens on for everything it would otherwise have had to ask for again:
/// another phone's battery, a device appearing or going away, an account being linked — and the
/// signalling that sets up a media connection between two of them.
///
/// Every connection joins one group named for the signed-in account. Reaching the phones of a
/// linked account means sending to that account's group too, which the notifier works out — a
/// connection is never put in another account's group, so a link being dropped takes effect at
/// once rather than leaving a subscription behind.
///
/// A connection that names its device joins a second group of its own. An offer is for one phone,
/// not for an account, and that group is how it gets there.
/// </summary>
[Authorize]
public sealed class DeviceHub : Hub
{
    /// <summary>Room for an SDP with video and a long candidate list, and not much more.</summary>
    private const int MaxBodyLength = 16 * 1024;

    private const string DeviceItemKey = "deviceId";

    private static readonly JsonSerializerOptions s_payloadOptions = new(JsonSerializerDefaults.Web);

    private readonly IDeviceRepository m_deviceRepository;
    private readonly IPairingRepository m_pairingRepository;
    private readonly DeviceConnections m_connections;
    private readonly ILogger<DeviceHub> m_logger;

    public DeviceHub(
        IDeviceRepository deviceRepository,
        IPairingRepository pairingRepository,
        DeviceConnections connections,
        ILogger<DeviceHub> logger)
    {
        m_deviceRepository = deviceRepository;
        m_pairingRepository = pairingRepository;
        m_connections = connections;
        m_logger = logger;
    }

    public override async Task OnConnectedAsync()
    {
        // [Authorize] is what guarantees the principal is here; the hub is never reached without it.
        var user = Context.User!;
        var userId = user.GetUserId();
        await Groups.AddToGroupAsync(Context.ConnectionId, GroupName(userId));

        var device = await ConnectingDeviceAsync(userId);
        if (device is not null)
        {
            Context.Items[DeviceItemKey] = device.Id;
            m_connections.Add(device.Id, Context.ConnectionId);
            await Groups.AddToGroupAsync(Context.ConnectionId, DeviceGroupName(device.Id));
            await AnnounceAsync(userId, device.Id, online: true);
        }

        // Hub calls arrive over the socket, bypassing the pipeline that tags HTTP requests with
        // the caller, so the name is read off the same claim here.
        m_logger.LogInformation(
            "SignalR: user {UserId} {Username} joined the device feed as device {DeviceId}",
            userId, user.Identity?.Name, device?.Id);

        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        if (Context.Items.TryGetValue(DeviceItemKey, out var deviceId) && deviceId is Guid id)
        {
            m_connections.Remove(id, Context.ConnectionId);

            // Only when the last of its connections has gone: a phone holds one per screen that
            // listens, and one of them closing does not mean the phone has.
            var gone = !m_connections.IsConnected(id);
            if (gone)
            {
                await AnnounceAsync(Context.User!.GetUserId(), id, online: false);
            }

            // A phone dropping off is what "not reachable" in the app means, and the exception is
            // the difference between it being closed and it being lost.
            m_logger.LogInformation(
                exception,
                "SignalR: device {DeviceId} closed a connection and is now {Presence}",
                id,
                gone ? "offline" : "still connected on another");
        }

        await base.OnDisconnectedAsync(exception);
    }

    /// <summary>
    /// Tells everyone who can see this phone that it has arrived or gone. A device list that says
    /// a phone exists is no use at three in the morning; what matters is whether it can answer.
    /// </summary>
    private async Task AnnounceAsync(Guid ownerId, Guid deviceId, bool online)
    {
        var accounts = (await m_pairingRepository.LinkedAccountsAsync(ownerId))
            .Append(ownerId)
            .Distinct()
            .Select(GroupName)
            .ToList();

        var payload = JsonSerializer.Serialize(
            new DevicePresenceDto { DeviceId = deviceId, IsOnline = online },
            s_payloadOptions);

        await Clients.Groups(accounts).SendAsync(DeviceEvents.DevicePresence, payload);
    }

    /// <summary>
    /// Passes one signalling message to one other phone, and reports whether that phone was
    /// connected to hand it to. The body is never read here: the offer, the answer and the
    /// candidates are between the two phones, and the keys protecting the media are agreed inside
    /// them where this server cannot reach.
    /// </summary>
    public async Task<bool> Signal(string payload)
    {
        var signal = Parse(payload);
        if (Context.Items[DeviceItemKey] is not Guid fromDeviceId)
        {
            // Without a device header there is no return address, and an offer nobody can answer
            // is worse than a refusal.
            m_logger.LogWarning(
                "SignalR: a connection with no device header tried to send a {Kind}", signal.Kind);
            throw new HubException("This connection did not say which phone it is.");
        }

        var userId = Context.User!.GetUserId();
        var target = await m_deviceRepository.FindAsync(signal.ToDeviceId);
        if (target is null || !await CanReachAsync(userId, target.UserId))
        {
            // One message for "no such phone" and "not yours to call", so device ids cannot be
            // probed from here any more than they can over HTTP. The log keeps the distinction,
            // because a run of these is the one sign of somebody sweeping for device ids.
            m_logger.LogWarning(
                "SignalR: device {FromDeviceId} may not signal {ToDeviceId} ({Reason})",
                fromDeviceId, signal.ToDeviceId, target is null ? "no such device" : "not linked");
            throw new HubException("That phone is not one this account can reach.");
        }

        var outgoing = new SignalDto
        {
            ToDeviceId = target.Id,
            FromDeviceId = fromDeviceId,
            Kind = signal.Kind,
            Body = signal.Body,

            // Carried across exactly as they arrived. These are what let the phone at the other
            // end tell a genuine offer from one this server made up, so a server that quietly
            // dropped or altered them would be defeating the only check on itself.
            SentAt = signal.SentAt,
            Signature = signal.Signature
        };

        await Clients.Group(DeviceGroupName(target.Id))
            .SendAsync(DeviceEvents.Signal, JsonSerializer.Serialize(outgoing, s_payloadOptions));

        var delivered = m_connections.IsConnected(target.Id);
        m_logger.LogInformation(
            "SignalR: {Kind} from device {FromDeviceId} to {ToDeviceId} ({Delivery})",
            signal.Kind, fromDeviceId, target.Id, delivered ? "connected" : "nobody listening");

        return delivered;
    }

    public static string GroupName(Guid userId) => $"account-{userId}";

    public static string DeviceGroupName(Guid deviceId) => $"device-{deviceId}";

    private SignalDto Parse(string payload)
    {
        SignalDto? signal = null;
        try
        {
            signal = JsonSerializer.Deserialize<SignalDto>(payload, s_payloadOptions);
        }
        catch (JsonException)
        {
            // Falls through to the same refusal as a well-formed message with nothing in it.
        }

        if (signal is null || !SignalKinds.IsKnown(signal.Kind))
        {
            m_logger.LogWarning(
                "SignalR: refused a signal that was malformed or of an unknown kind ({Length} bytes)",
                payload.Length);
            throw new HubException("That is not a signal this server carries.");
        }

        if (signal.Body.Length > MaxBodyLength)
        {
            m_logger.LogWarning(
                "SignalR: refused a {Kind} of {Length} bytes, over the {Limit} byte limit",
                signal.Kind, signal.Body.Length, MaxBodyLength);
            throw new HubException("That signal is too long to be an offer.");
        }

        return signal;
    }

    /// <summary>The phones an account may call: its own, and those of every account linked to it.</summary>
    private async Task<bool> CanReachAsync(Guid userId, Guid ownerId) =>
        ownerId == userId || (await m_pairingRepository.LinkedAccountsAsync(userId)).Contains(ownerId);

    private async Task<Device?> ConnectingDeviceAsync(Guid userId)
    {
        var httpContext = Context.GetHttpContext();
        if (httpContext is null || !Guid.TryParse(httpContext.Request.Headers[DeviceHeaderExtension.DeviceIdHeader], out var deviceId))
        {
            return null;
        }

        var device = await m_deviceRepository.FindAsync(deviceId);
        return device?.UserId == userId ? device : null;
    }
}

using System.Text.Json;
using Babymonitor.Auth;
using Babymonitor.Realtime;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace Babymonitor.Hubs;

/// <summary>
/// Relays signalling between phones addressed by device id, and tells watchers when a phone comes
/// and goes. Whether a sender is trusted is decided on the phones, from signatures this hub passes
/// through untouched.
/// </summary>
[Authorize]
public sealed class DeviceHub : Hub
{
    // Room for an SDP with video and a long candidate list.
    private const int MaxBodyLength = 16 * 1024;
    private const int MaxWatchedDevices = 64;
    private const string WatchedItemKey = "watched";

    // Payloads go out as JSON strings, camelCase, so the phones parse them like any other JSON.
    private static readonly JsonSerializerOptions s_payloadOptions = new(JsonSerializerDefaults.Web)
    {
        RespectNullableAnnotations = true
    };

    private readonly DeviceConnections m_connections;
    private readonly ILogger<DeviceHub> m_logger;

    public DeviceHub(DeviceConnections connections, ILogger<DeviceHub> logger)
    {
        m_connections = connections;
        m_logger = logger;
    }

    private string CallerId => Context.UserIdentifier!;

    public static string DeviceGroup(string deviceId) => $"device-{deviceId}";

    public static string WatchGroup(string deviceId) => $"watch-{deviceId}";

    public override async Task OnConnectedAsync()
    {
        await Groups.AddToGroupAsync(Context.ConnectionId, DeviceGroup(CallerId));

        var cameOnline = m_connections.Add(CallerId, Context.ConnectionId);
        if (cameOnline)
        {
            await AnnouncePresenceAsync(CallerId, isOnline: true);
        }

        m_logger.LogInformation("SignalR: device {DeviceId} connected", CallerId);
        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var wentOffline = m_connections.Remove(CallerId, Context.ConnectionId);
        if (wentOffline)
        {
            await AnnouncePresenceAsync(CallerId, isOnline: false);
        }

        m_logger.LogInformation(exception, "SignalR: device {DeviceId} disconnected", CallerId);
        await base.OnDisconnectedAsync(exception);
    }

    /// <summary>Passes one message to another phone and says whether that phone is connected.</summary>
    public async Task<bool> Signal(string payload)
    {
        var signal = Parse(payload) with { FromDeviceId = CallerId };

        await Clients.Group(DeviceGroup(signal.ToDeviceId))
            .SendAsync(DeviceEvents.Signal, JsonSerializer.Serialize(signal, s_payloadOptions));

        var delivered = m_connections.IsOnline(signal.ToDeviceId);
        m_logger.LogInformation(
            "SignalR: {Kind} from device {FromDeviceId} to {ToDeviceId} ({Delivery})",
            signal.Kind, signal.FromDeviceId, signal.ToDeviceId, delivered ? "connected" : "nobody listening");

        return delivered;
    }

    /// <summary>Replaces the set of devices this connection hears presence for; returns those online now.</summary>
    public async Task<string[]> Watch(string[]? deviceIds)
    {
        var watched = (deviceIds ?? [])
            .Where(DeviceId.IsValid)
            .Distinct(StringComparer.Ordinal)
            .Take(MaxWatchedDevices)
            .ToArray();

        if (Context.Items.TryGetValue(WatchedItemKey, out var previous) && previous is string[] previouslyWatched)
        {
            foreach (var deviceId in previouslyWatched)
            {
                await Groups.RemoveFromGroupAsync(Context.ConnectionId, WatchGroup(deviceId));
            }
        }

        foreach (var deviceId in watched)
        {
            await Groups.AddToGroupAsync(Context.ConnectionId, WatchGroup(deviceId));
        }

        Context.Items[WatchedItemKey] = watched;
        return watched.Where(m_connections.IsOnline).ToArray();
    }

    private Task AnnouncePresenceAsync(string deviceId, bool isOnline) =>
        Clients.Group(WatchGroup(deviceId)).SendAsync(
            DeviceEvents.DevicePresence,
            JsonSerializer.Serialize(new DevicePresence(deviceId, isOnline), s_payloadOptions));

    private SignalMessage Parse(string payload)
    {
        SignalMessage? signal;
        try
        {
            signal = JsonSerializer.Deserialize<SignalMessage>(payload, s_payloadOptions);
        }
        catch (JsonException)
        {
            signal = null;
        }

        if (signal is null || !SignalKinds.IsKnown(signal.Kind))
        {
            throw Refuse("malformed or of an unknown kind", "That is not a signal this server carries.");
        }

        if (signal.Body.Length > MaxBodyLength)
        {
            throw Refuse($"body of {signal.Body.Length} characters is over the limit", "That signal is too long.");
        }

        if (!DeviceId.IsValid(signal.ToDeviceId))
        {
            throw Refuse("toDeviceId is not a device id", "That is not a device id.");
        }

        return signal;
    }

    private HubException Refuse(string reason, string message)
    {
        m_logger.LogWarning("SignalR: refused a signal from device {DeviceId}: {Reason}", CallerId, reason);
        return new HubException(message);
    }
}

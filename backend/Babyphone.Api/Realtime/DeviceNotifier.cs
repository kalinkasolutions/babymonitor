using Babyphone.Hubs;
using Dal.Repositories.Devices;
using Dtos.Devices;
using Microsoft.AspNetCore.SignalR;
using System.Text.Json;

namespace Babyphone.Realtime;

public sealed class DeviceNotifier : IDeviceNotifier
{
    private readonly IHubContext<DeviceHub> m_hub;
    private readonly IPairingRepository m_pairingRepository;
    private readonly ILogger<DeviceNotifier> m_logger;

    /// <summary>
    /// Payloads go out as JSON text rather than as objects, so the phones decode them with the
    /// same serializer they use for REST responses instead of relying on the SignalR client's own
    /// reflection over Kotlin types. camelCase to match those responses.
    /// </summary>
    private static readonly JsonSerializerOptions s_payloadOptions =
        new(JsonSerializerDefaults.Web);

    public DeviceNotifier(
        IHubContext<DeviceHub> hub,
        IPairingRepository pairingRepository,
        ILogger<DeviceNotifier> logger)
    {
        m_hub = hub;
        m_pairingRepository = pairingRepository;
        m_logger = logger;
    }

    public async Task DeviceChangedAsync(Guid ownerId, DeviceDto device)
    {
        var accounts = await AudienceAsync(ownerId);
        m_logger.LogInformation(
            "SignalR: device {DeviceId} changed, telling {GroupCount} account(s)", device.Id, accounts.Count);

        // One message per account, not one message to all of them. "Mine" is a statement about
        // the reader, and a device described for its owner arriving at somebody else's phone
        // tells that phone it owns a device it cannot touch.
        foreach (var account in accounts)
        {
            var seenByThem = device with
            {
                IsMine = device.OwnerId == account,

                // Which phone is asking is a property of a connection, not of an account group,
                // and the phones work it out for themselves from the key they hold.
                IsThisDevice = false
            };

            await m_hub.Clients.Group(DeviceHub.GroupName(account)).SendAsync(
                DeviceEvents.DeviceChanged, JsonSerializer.Serialize(seenByThem, s_payloadOptions));
        }
    }

    public async Task DeviceRemovedAsync(Guid ownerId, Guid deviceId)
    {
        var groups = (await AudienceAsync(ownerId)).Select(DeviceHub.GroupName).ToList();
        await m_hub.Clients.Groups(groups).SendAsync(DeviceEvents.DeviceRemoved, deviceId.ToString());
    }

    public Task LinksChangedAsync(Guid userId, Guid otherUserId)
    {
        // Both sides gained or lost sight of the other's phones, so both reload rather than being
        // sent a device each — the change is to the set, not to any one device in it.
        var groups = new[] { DeviceHub.GroupName(userId), DeviceHub.GroupName(otherUserId) };
        return m_hub.Clients.Groups(groups).SendAsync(DeviceEvents.LinksChanged);
    }

    public Task PairingCompletedAsync(Guid userId, Guid otherUserId, PairingCompletedDto completed)
    {
        // Both accounts, even when they are the same one: scanning your own second phone
        // confirms its key without changing any link, and that phone still has to be told.
        var groups = new[] { DeviceHub.GroupName(userId), DeviceHub.GroupName(otherUserId) }
            .Distinct()
            .ToList();

        // The proof passes through untouched and is never stored. The backend cannot check it
        // and must not be able to forge it — the secret behind it went from screen to camera and
        // was never sent here.
        return m_hub.Clients.Groups(groups).SendAsync(
            DeviceEvents.PairingCompleted, JsonSerializer.Serialize(completed, s_payloadOptions));
    }

    /// <summary>
    /// The owner's account and every account linked to it — exactly the set that
    /// <c>GET /api/devices</c> would have shown this device to.
    /// </summary>
    private async Task<IReadOnlyList<Guid>> AudienceAsync(Guid ownerId)
    {
        var linked = await m_pairingRepository.LinkedAccountsAsync(ownerId);
        return linked.Append(ownerId).Distinct().ToList();
    }
}

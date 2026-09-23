using Dtos.Devices;

namespace Babymonitor.Realtime;

/// <summary>
/// The realtime broadcasts endpoints fire after a successful change, so no phone ever has to poll
/// for one. Lives in the Api layer alongside the hub; the BusinessLogic services deliberately know
/// nothing about SignalR.
/// </summary>
public interface IDeviceNotifier
{
    /// <summary>Tells everyone who can see this device that it changed.</summary>
    Task DeviceChangedAsync(Guid ownerId, DeviceDto device);

    Task DeviceRemovedAsync(Guid ownerId, Guid deviceId);

    /// <summary>Tells both sides of a new or dropped link to reload what they can see.</summary>
    Task LinksChangedAsync(Guid userId, Guid otherUserId);

    /// <summary>
    /// Tells both sides a scan finished, so neither is left waiting on the pairing screen — and
    /// hands the phone that showed the code the proof it needs to trust the one that scanned it.
    /// </summary>
    Task PairingCompletedAsync(Guid userId, Guid otherUserId, PairingCompletedDto completed);
}

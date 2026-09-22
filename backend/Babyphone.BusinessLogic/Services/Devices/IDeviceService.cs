using Dtos.Devices;
using Shared;

namespace Babyphone.BusinessLogic.Services.Devices;

/// <summary>
/// The account's phones. Every method takes the caller's user id, so one account can never see
/// or touch another's devices.
/// </summary>
public interface IDeviceService
{
    Task<OperationResult<List<DeviceDto>>> ListAsync(Guid userId, Guid? thisDeviceId);

    /// <summary>One of the caller's own devices, as the list would show it.</summary>
    Task<OperationResult<DeviceDto>> FindAsync(Guid userId, Guid deviceId, Guid? thisDeviceId);

    /// <summary>
    /// Registers this phone, or returns the existing row if the same key has registered before —
    /// reinstalling the app must not leave a trail of dead devices behind.
    /// </summary>
    Task<OperationResult<DeviceDto>> RegisterAsync(Guid userId, RegisterDeviceDto dto);

    Task<OperationResult<DeviceDto>> RenameAsync(
        Guid userId, Guid deviceId, Guid? thisDeviceId, RenameDeviceDto dto);

    /// <summary>Records liveness and battery. The absence of these is what the offline alarm watches.</summary>
    Task<OperationResult<Empty>> HeartbeatAsync(Guid userId, Guid deviceId, HeartbeatDto dto);

    Task<OperationResult<Empty>> RevokeAsync(Guid userId, Guid deviceId);
}

using Shared;
using Entities.Devices;

namespace Dal.Repositories.Devices;

public interface IDeviceRepository
{
    Task<List<Device>> ListAsync(Guid userId);
    Task<Device?> FindAsync(Guid deviceId);
    Task<List<Device>> ListByOwnersAsync(IReadOnlyCollection<Guid> userIds);

    /// <summary>Display names for the owners of a set of devices, so a paired phone can be attributed.</summary>
    Task<Dictionary<Guid, string>> OwnerNamesAsync(IReadOnlyCollection<Guid> userIds);

    /// <summary>The account's device holding this public key, if it has already registered.</summary>
    Task<Device?> FindByPublicKeyAsync(Guid userId, string publicKey);

    Task<OperationResult<Device>> AddAsync(Device device);
    Task<OperationResult<Empty>> SaveAsync();
    Task<OperationResult<Empty>> RemoveAsync(Device device);
}
